/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.services.s3.internal.multipart;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.annotations.Immutable;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.annotations.ThreadSafe;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;

/**
 * A subscriber implementation that will download all individual parts for a multipart presigned URL download request.
 * It receives individual {@link AsyncResponseTransformer} instances which will be used to perform the individual 
 * range-based part requests using presigned URLs. This is a 'one-shot' class, it should <em>NOT</em> be reused 
 * for more than one multipart download.
 * 
 * <p>Unlike the standard {@link MultipartDownloaderSubscriber} which uses S3's native multipart API with part numbers,
 * this subscriber uses HTTP range requests against presigned URLs to achieve multipart download functionality.
 * <p>This implementation is thread-safe and handles concurrent part downloads while maintaining proper
 * ordering and validation of responses.</p>
 */
@ThreadSafe
@Immutable
@SdkInternalApi
public class PresignedUrlMultipartDownloaderSubscriber 
    implements Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> {

    private static final Logger log = Logger.loggerFor(PresignedUrlMultipartDownloaderSubscriber.class);
    private static final String BYTES_RANGE_PREFIX = "bytes=";
    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");

    /**
     * The S3 client used to make the individual presigned URL requests.
     */
    private final S3AsyncClient s3;

    /**
     * The base PresignedUrlDownloadRequest that was provided when calling the download method.
     * It is copied for each individual request, and the copy has the range field updated 
     * as more parts are downloaded.
     */
    private final PresignedUrlDownloadRequest presignedUrlDownloadRequest;

    /**
     * The configured part size in bytes for multipart downloads. This represents the desired
     * size for each part, though the actual part size may be adjusted based on the total object size.
     */
    private final long configuredPartSizeInBytes;

    /**
     * This future will be completed once this subscriber reaches a terminal state, failed or successfully,
     * and will be completed accordingly.
     */
    private final CompletableFuture<Void> future;

    /**
     * The subscription lock used to ensure thread-safe access to subscription operations.
     */
    private final Object lock = new Object();

    /**
     * This value contains the multipart download state including total size, part information,
     * and progress tracking. If null, it means we haven't received a response from S3 yet to 
     * initialize the state, or the size discovery is still in progress.
     */
    private volatile MultipartDownloadState state;

    /**
     * The subscription received from the publisher this subscriber subscribes to.
     */
    private Subscription subscription;

    private static class MultipartDownloadState {
        final long totalContentLength;
        final long actualPartSizeInBytes;
        final int totalParts;
        final AtomicInteger completedParts;
        final String etag;
        final int expectedRequestCount;

        MultipartDownloadState(long totalLength, long partSize, int totalParts, String etag) {
            this.totalContentLength = totalLength;
            this.actualPartSizeInBytes = partSize;
            this.totalParts = totalParts;
            this.completedParts = new AtomicInteger(1);
            this.etag = etag;
            this.expectedRequestCount = totalParts;
        }
    }

    /**
     * Creates a new PresignedUrlMultipartDownloaderSubscriber with the specified parameters.
     *
     * @param s3 the S3AsyncClient to use for making requests, must not be null
     * @param presignedUrlDownloadRequest the base PresignedUrlDownloadRequest to copy for each part, must not be null
     * @param configuredPartSizeInBytes the desired part size in bytes, must be positive
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public PresignedUrlMultipartDownloaderSubscriber(
            S3AsyncClient s3,
            PresignedUrlDownloadRequest presignedUrlDownloadRequest,
            long configuredPartSizeInBytes) {
        this.s3 = Validate.paramNotNull(s3, "s3AsyncClient");
        this.presignedUrlDownloadRequest = Validate.paramNotNull(presignedUrlDownloadRequest, "presignedUrlDownloadRequest");
        Validate.isPositive(configuredPartSizeInBytes, "configuredPartSizeInBytes");
        this.configuredPartSizeInBytes = configuredPartSizeInBytes;
        this.future = new CompletableFuture<>();
    }

    /**
     * {@inheritDoc}
     *
     * Handles subscription setup with thread-safe access control. Only accepts one subscription
     * and cancels any additional subscription attempts.
     */
    @Override
    public void onSubscribe(Subscription s) {
        synchronized (lock) {
            if (subscription != null) {
                s.cancel();
                return;
            }
            this.subscription = s;
            s.request(1);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Processes the next AsyncResponseTransformer for downloading a part. If this is the first
     * call (state is null), performs size discovery and downloads the first part. Otherwise,
     * downloads the next part in sequence.
     *
     * @param asyncResponseTransformer the transformer to use for the next part download, must not be null
     * @throws NullPointerException if asyncResponseTransformer is null
     */
    @Override
    public void onNext(AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> asyncResponseTransformer) {
        if (asyncResponseTransformer == null) {
            subscription.cancel();
            throw new NullPointerException("onNext must not be called with null transformer");
        }

        if (state == null) {
            performSizeDiscoveryAndFirstPart(asyncResponseTransformer);
        } else {
            downloadNextPart(asyncResponseTransformer);
        }
    }

    private void performSizeDiscoveryAndFirstPart(AsyncResponseTransformer<GetObjectResponse,
                                                    GetObjectResponse> asyncResponseTransformer) {
        long endByte = configuredPartSizeInBytes - 1;
        String firstPartRange = String.format("%s0-%d", BYTES_RANGE_PREFIX, endByte);
        
        PresignedUrlDownloadRequest firstPartRequest = presignedUrlDownloadRequest.toBuilder()
                                                                  .range(firstPartRange)
                                                                  .build();

        s3.presignedUrlExtension().getObject(firstPartRequest, asyncResponseTransformer)
            .whenComplete((response, error) -> {
                if (error != null) {
                    log.debug(() -> "Error encountered during first part request");
                    onError(error);
                    return;
                }

                try {
                    // Parse total size from Content-Range header
                    String contentRange = response.contentRange();
                    if (contentRange == null) {
                        onError(new IllegalStateException("No Content-Range header in response"));
                        return;
                    }

                    long totalSize = parseContentRangeForTotalSize(contentRange);

                    if (totalSize <= configuredPartSizeInBytes) {
                        subscription.cancel();
                        return;
                    }

                    String etag = response.eTag();
                    if (etag == null) {
                        onError(new IllegalStateException("No ETag in response, cannot ensure consistency"));
                        return;
                    }

                    initializeStateAfterFirstPart(totalSize, etag);

                    if (state.totalParts > 1) {
                        subscription.request(1);
                    } else {
                        subscription.cancel();
                    }

                } catch (Exception e) {
                    log.debug(() -> "Error during first part processing", e);
                    onError(e);
                }
            });
    }

    private void downloadNextPart(AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> transformer) {
        int nextPartIndex = state.completedParts.get();
        
        if (nextPartIndex >= state.totalParts) {
            try {
                validateTotalRequestCount();
                subscription.cancel();
            } catch (Exception validationError) {
                log.debug(() -> "Final validation failed", validationError);
                onError(validationError);
            }
            return;
        }

        PresignedUrlDownloadRequest partRequest = createPartRequest(nextPartIndex);
        String expectedRange = partRequest.range();

        s3.presignedUrlExtension().getObject(partRequest, transformer)
            .whenComplete((response, error) -> {
                if (error != null) {
                    log.debug(() -> "Error encountered during part request with range=" + expectedRange);
                    onError(error);
                } else {
                    try {
                        validatePartResponse(response, nextPartIndex, expectedRange);

                        int completedCount = state.completedParts.incrementAndGet();
                        if (completedCount < state.totalParts) {
                            subscription.request(1);
                        } else {
                            validateTotalRequestCount();
                            subscription.cancel();
                        }
                    } catch (Exception validationError) {
                        log.debug(() -> "Validation failed for part " + (nextPartIndex + 1));
                        onError(validationError);
                    }
                }
            });
    }

    private void initializeStateAfterFirstPart(long totalSize, String etag) {
        if (totalSize <= 0) {
            throw new IllegalArgumentException("Total size must be positive: " + totalSize);
        }
        if (etag == null || etag.trim().isEmpty()) {
            throw new IllegalArgumentException("ETag cannot be null or empty");
        }
        
        long optimalPartSize = calculateOptimalPartSize(totalSize);
        int totalParts = calculateTotalParts(totalSize, optimalPartSize);

        this.state = new MultipartDownloadState(totalSize, optimalPartSize, totalParts, etag.trim());
    }

    private long parseContentRangeForTotalSize(String contentRange) {
        if (contentRange == null || contentRange.trim().isEmpty()) {
            throw new IllegalArgumentException("Content-Range header is null or empty");
        }
        
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(contentRange.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Content-Range header format: " + contentRange + 
                                             ". Expected format: 'bytes start-end/total'");
        }

        try {
            long totalLength = Long.parseLong(matcher.group(3));
            if (totalLength <= 0) {
                throw new IllegalArgumentException("Invalid total length in Content-Range header: " + totalLength);
            }
            
            return totalLength;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format in Content-Range header: " + contentRange, e);
        }
    }

    private long calculateOptimalPartSize(long totalContentLength) {
        if (totalContentLength <= 0) {
            throw new IllegalArgumentException("Total content length must be positive: " + totalContentLength);
        }
        return Math.min(configuredPartSizeInBytes, totalContentLength);
    }

    private int calculateTotalParts(long contentLength, long partSize) {
        int totalParts = (int) Math.ceil((double) contentLength / partSize);
        return totalParts;
    }

    private PresignedUrlDownloadRequest createPartRequest(int partIndex) {
        if (state == null) {
            throw new IllegalStateException("Cannot create part request before state is initialized");
        }
        
        long startByte = partIndex * state.actualPartSizeInBytes;
        long endByte = Math.min(startByte + state.actualPartSizeInBytes - 1, state.totalContentLength - 1);

        if (startByte < 0) {
            throw new IllegalStateException("Start byte cannot be negative: " + startByte);
        }
        if (startByte >= state.totalContentLength) {
            throw new IllegalStateException(String.format(
                "Start byte (%d) exceeds total content length (%d) for part %d", 
                startByte, state.totalContentLength, partIndex));
        }
        if (endByte < startByte) {
            throw new IllegalStateException(String.format(
                "End byte (%d) is less than start byte (%d) for part %d", 
                endByte, startByte, partIndex));
        }

        String rangeHeader = String.format("%s%d-%d", BYTES_RANGE_PREFIX, startByte, endByte);

        return presignedUrlDownloadRequest.toBuilder()
                .range(rangeHeader)
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * Handles errors by completing the future exceptionally and logging the error.
     */
    @Override
    public void onError(Throwable t) {
        log.debug(() -> "Error in multipart download", t);
        future.completeExceptionally(t);
    }

    /**
     * {@inheritDoc}
     *
     * Handles successful completion by completing the future normally.
     */
    @Override
    public void onComplete() {
        future.complete(null);
    }

    /**
     * Returns the CompletableFuture that will be completed when this subscriber reaches
     * a terminal state (either successfully or with an error).
     *
     * @return the future representing the completion of this subscriber
     */
    public CompletableFuture<Void> future() {
        return this.future;
    }

    private void validatePartResponse(GetObjectResponse response, int partIndex, String expectedRange) {
        String actualContentRange = response.contentRange();
        if (actualContentRange == null) {
            throw new IllegalStateException("Missing Content-Range header in part response");
        }

        String expectedRangeValue = expectedRange.replace(BYTES_RANGE_PREFIX, "");
        if (!actualContentRange.contains(expectedRangeValue)) {
            throw new IllegalStateException(String.format(
                "Content-Range mismatch: expected %s, got %s", expectedRange, actualContentRange));
        }

        long expectedPartSize = calculateExpectedPartSize(partIndex);
        Long actualContentLength = response.contentLength();
        if (actualContentLength == null || actualContentLength != expectedPartSize) {
            throw new IllegalStateException(String.format(
                "Part size mismatch: expected %d bytes, got %s bytes", expectedPartSize, actualContentLength));
        }

        String responseETag = response.eTag();
        if (responseETag != null && state.etag != null && !state.etag.equals(responseETag)) {
            throw new IllegalStateException("ETag mismatch - object may have changed during download");
        }
    }

    private long calculateExpectedPartSize(int partIndex) {
        long startByte = partIndex * state.actualPartSizeInBytes;
        long endByte = Math.min(startByte + state.actualPartSizeInBytes - 1, state.totalContentLength - 1);
        return endByte - startByte + 1;
    }

    private void validateTotalRequestCount() {
        int actualRequests = state.completedParts.get();
        if (actualRequests != state.expectedRequestCount) {
            throw new IllegalStateException(String.format(
                "Request count validation failed: expected %d, actual %d", 
                state.expectedRequestCount, actualRequests));
        }
    }
}
