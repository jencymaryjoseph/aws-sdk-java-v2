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

import java.util.Optional;
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
import software.amazon.awssdk.services.s3.internal.presignedurl.model.PresignedUrlDownloadRequestWrapper;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
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

    private final S3AsyncClient s3AsyncClient;
    private final PresignedUrlDownloadRequest presignedUrlDownloadRequest;
    private final long configuredPartSizeInBytes;
    private final int completedParts;
    private final CompletableFuture<Void> future;
    private final Object lock = new Object();
    private volatile MultipartDownloadState state;
    private Subscription subscription;
    private final MultipartDownloadResumeContext resumeContext;

    private static class MultipartDownloadState {
        final long totalContentLength;
        final long actualPartSizeInBytes;
        final int totalParts;
        final AtomicInteger completedParts;
        final String etag;

        MultipartDownloadState(long totalLength, long partSize, int totalParts, String etag, int completedParts) {
            this.totalContentLength = totalLength;
            this.actualPartSizeInBytes = partSize;
            this.totalParts = totalParts;
            this.completedParts = new AtomicInteger(completedParts);
            this.etag = etag;
        }
    }

    public PresignedUrlMultipartDownloaderSubscriber(
        S3AsyncClient s3AsyncClient,
        PresignedUrlDownloadRequest presignedUrlDownloadRequest,
        long configuredPartSizeInBytes) {
        this(s3AsyncClient, presignedUrlDownloadRequest, configuredPartSizeInBytes, 0);
    }

    public PresignedUrlMultipartDownloaderSubscriber(
        S3AsyncClient s3AsyncClient,
        PresignedUrlDownloadRequest presignedUrlDownloadRequest,
        long configuredPartSizeInBytes,
        int completedParts) {
        this.s3AsyncClient = s3AsyncClient;
        this.presignedUrlDownloadRequest = presignedUrlDownloadRequest;
        this.configuredPartSizeInBytes = configuredPartSizeInBytes;
        this.completedParts = completedParts;
        this.future = new CompletableFuture<>();
        this.resumeContext = null;
    }

    public PresignedUrlMultipartDownloaderSubscriber(
        S3AsyncClient s3AsyncClient,
        PresignedUrlDownloadRequest presignedUrlDownloadRequest,
        long configuredPartSizeInBytes,
        MultipartDownloadResumeContext resumeContext) {
        this.s3AsyncClient = s3AsyncClient;
        this.presignedUrlDownloadRequest = presignedUrlDownloadRequest;
        this.configuredPartSizeInBytes = configuredPartSizeInBytes;
        this.completedParts = resumeContext != null ? resumeContext.highestSequentialCompletedPart() : 0;
        this.future = new CompletableFuture<>();
        this.resumeContext = resumeContext;
    }



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

    @Override
    public void onNext(AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> asyncResponseTransformer) {
        if (asyncResponseTransformer == null) {
            subscription.cancel();
            throw new NullPointerException("onNext must not be called with null asyncResponseTransformer");
        }

        if (state == null) {
            performSizeDiscoveryAndFirstPart(asyncResponseTransformer);
        } else {
            downloadNextPart(asyncResponseTransformer);
        }
    }

    private void performSizeDiscoveryAndFirstPart(AsyncResponseTransformer<GetObjectResponse,
        GetObjectResponse> asyncResponseTransformer) {
        // If resuming, skip the first part if it's already completed
        if (completedParts > 0) {
            performSizeDiscoveryOnly(asyncResponseTransformer);
            return;
        }
        
        long endByte = configuredPartSizeInBytes - 1;
        String firstPartRange = String.format("%s0-%d", BYTES_RANGE_PREFIX, endByte);

        PresignedUrlDownloadRequest firstPartRequest = presignedUrlDownloadRequest.toBuilder()
                                                                                  .range(firstPartRange)
                                                                                  .build();

        s3AsyncClient.presignedUrlExtension().getObject(firstPartRequest, asyncResponseTransformer)
          .whenComplete((response, error) -> {
              if (error != null) {
                  log.debug(() -> "Error encountered during first part request");
                  onError(error);
                  return;
              }

              try {
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
                  updateResumeContextWithTotalParts(state.totalParts);
                  updateResumeContext(response, 1);

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
    
    private void performSizeDiscoveryOnly(AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> asyncResponseTransformer) {
        // For resume, we need to discover the total size without downloading the first part
        String sizeDiscoveryRange = String.format("%s0-0", BYTES_RANGE_PREFIX);
        
        PresignedUrlDownloadRequest sizeDiscoveryRequest = presignedUrlDownloadRequest.toBuilder()
                                                                                      .range(sizeDiscoveryRange)
                                                                                      .build();
        
        s3AsyncClient.presignedUrlExtension().getObject(sizeDiscoveryRequest, asyncResponseTransformer)
          .whenComplete((response, error) -> {
              if (error != null) {
                  log.debug(() -> "Error encountered during size discovery request");
                  onError(error);
                  return;
              }
              
              try {
                  String contentRange = response.contentRange();
                  if (contentRange == null) {
                      onError(new IllegalStateException("No Content-Range header in response"));
                      return;
                  }
                  
                  long totalSize = parseContentRangeForTotalSize(contentRange);
                  String etag = response.eTag();
                  
                  if (etag == null) {
                      onError(new IllegalStateException("No ETag in response, cannot ensure consistency"));
                      return;
                  }
                  
                  int totalParts = calculateTotalParts(totalSize, configuredPartSizeInBytes);
                  this.state = new MultipartDownloadState(totalSize, configuredPartSizeInBytes, totalParts, etag, completedParts);
                  
                  updateResumeContextWithTotalParts(state.totalParts);
                  
                  if (completedParts < state.totalParts) {
                      subscription.request(1);
                  } else {
                      subscription.cancel();
                  }
                  
              } catch (Exception e) {
                  log.debug(() -> "Error during size discovery processing", e);
                  onError(e);
              }
          });
    }

    private void downloadNextPart(AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> transformer) {
        int nextPartIndex = state.completedParts.get();

        if (nextPartIndex >= state.totalParts) {
            subscription.cancel();
            return;
        }

        PresignedUrlDownloadRequest partRequest = createPartRequest(nextPartIndex);
        String expectedRange = partRequest.range();

        s3AsyncClient.presignedUrlExtension().getObject(partRequest, transformer)
          .whenComplete((response, error) -> {
              if (error != null) {
                  log.debug(() -> "Error encountered during part request with range=" + expectedRange);
                  onError(error);
              } else {
                  try {
                      validatePartResponse(response, nextPartIndex, expectedRange);

                      int completedCount = state.completedParts.incrementAndGet();
                      updateResumeContext(response, completedCount);
                      
                      if (completedCount < state.totalParts) {
                          subscription.request(1);
                      } else {
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
        int totalParts = calculateTotalParts(totalSize, configuredPartSizeInBytes);
        this.state = new MultipartDownloadState(totalSize, configuredPartSizeInBytes, totalParts, etag, completedParts + 1);
    }

    private long parseContentRangeForTotalSize(String contentRange) {
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(contentRange);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Content-Range header: " + contentRange);
        }
        return Long.parseLong(matcher.group(3));
    }

    private int calculateTotalParts(long contentLength, long partSize) {
        return (int) Math.ceil((double) contentLength / partSize);
    }

    private PresignedUrlDownloadRequest createPartRequest(int partIndex) {
        long startByte = partIndex * state.actualPartSizeInBytes;
        long endByte = Math.min(startByte + state.actualPartSizeInBytes - 1, state.totalContentLength - 1);
        String rangeHeader = String.format("%s%d-%d", BYTES_RANGE_PREFIX, startByte, endByte);
        
        return presignedUrlDownloadRequest.toBuilder()
                                          .range(rangeHeader)
                                          .build();
    }

    @Override
    public void onError(Throwable t) {
        log.debug(() -> "Error in multipart download", t);
        future.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        future.complete(null);
    }

    public CompletableFuture<Void> future() {
        return this.future;
    }

    private void validatePartResponse(GetObjectResponse response, int partIndex, String expectedRange) {
        String responseETag = response.eTag();
        if (responseETag != null && state.etag != null && !state.etag.equals(responseETag)) {
            throw new IllegalStateException("ETag mismatch - object may have changed during download");
        }
    }

    private void updateResumeContext(GetObjectResponse response, int completedPart) {
        if (resumeContext != null) {
            resumeContext.addCompletedPart(completedPart);
            resumeContext.addToBytesToLastCompletedParts(response.contentLength());
            if (resumeContext.response() == null) {
                resumeContext.response(response);
            }
        }
    }

    private void updateResumeContextWithTotalParts(int totalParts) {
        if (resumeContext != null) {
            resumeContext.totalParts(totalParts);
        }
    }
}