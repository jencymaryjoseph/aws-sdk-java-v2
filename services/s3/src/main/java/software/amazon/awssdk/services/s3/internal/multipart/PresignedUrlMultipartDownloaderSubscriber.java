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
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;

@SdkInternalApi
public class PresignedUrlMultipartDownloaderSubscriber
        implements Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> {

    private static final Logger log = Logger.loggerFor(PresignedUrlMultipartDownloaderSubscriber.class);
    private static final String BYTES_RANGE_PREFIX = "bytes=";

    private final S3AsyncClient s3;
    private final PresignedUrlDownloadRequest baseRequest;
    private final long totalContentLength;
    private final long partSizeInBytes;
    private final int totalParts;
    private final AtomicInteger completedParts;
    private final CompletableFuture<Void> future;
    private final Object lock = new Object();

    private Subscription subscription;

    public PresignedUrlMultipartDownloaderSubscriber(
            S3AsyncClient s3,
            PresignedUrlDownloadRequest baseRequest,
            long totalContentLength,
            long partSizeInBytes) {
        this(s3, baseRequest, totalContentLength, partSizeInBytes, 0);
    }

    public PresignedUrlMultipartDownloaderSubscriber(
            S3AsyncClient s3,
            PresignedUrlDownloadRequest baseRequest,
            long totalContentLength,
            long partSizeInBytes,
            int completedParts) {
        this.s3 = Validate.paramNotNull(s3, "s3AsyncClient");
        this.baseRequest = Validate.paramNotNull(baseRequest, "baseRequest");
        this.totalContentLength = totalContentLength;
        this.partSizeInBytes = partSizeInBytes;
        this.totalParts = calculateTotalParts(totalContentLength, partSizeInBytes);
        this.completedParts = new AtomicInteger(completedParts);
        this.future = new CompletableFuture<>();
    }

    private int calculateTotalParts(long contentLength, long partSize) {
        return (int) Math.ceil((double) contentLength / partSize);
    }

    @Override
    public void onSubscribe(Subscription s) {
        synchronized (lock) {
            if (subscription != null) {
                s.cancel();
                return;
            }
            this.subscription = s;
            this.subscription.request(1);
        }
    }

    @Override
    public void onNext(AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> asyncResponseTransformer) {
        if (asyncResponseTransformer == null) {
            subscription.cancel();
            throw new NullPointerException("onNext must not be called with null asyncResponseTransformer");
        }

        int nextPartIndex = completedParts.get(); // 0-based index

        if (nextPartIndex >= totalParts) {
            log.debug(() -> String.format("Completing multipart download after a total of %d parts downloaded.", totalParts));
            subscription.cancel();
            return;
        }

        PresignedUrlDownloadRequest partRequest = createPartRequest(nextPartIndex);
        log.debug(() -> String.format("Sending presigned URL request for part %d (range: %s)",
                nextPartIndex + 1, partRequest.range()));

        CompletableFuture<GetObjectResponse> getObjectFuture =
                s3.presignedUrlExtension().getObject(partRequest, asyncResponseTransformer);

        getObjectFuture.whenComplete((response, error) -> {
            if (error != null) {
                log.debug(() -> "Error encountered during presigned URL request for part " + (nextPartIndex + 1), error);
                onError(error);
            } else {
                requestMoreIfNeeded(response);
            }
        });
    }

    @Override
    public void onError(Throwable t) {
        future.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        future.complete(null);
    }

    public CompletableFuture<Void> future() {
        return future;
    }

    private void requestMoreIfNeeded(GetObjectResponse response) {
        int totalComplete = completedParts.incrementAndGet();
        log.debug(() -> String.format("Completed part %d of %d", totalComplete, totalParts));

        if (totalComplete < totalParts) {
            subscription.request(1);
        } else {
            log.debug(() -> "All parts downloaded, completing multipart download");
            subscription.cancel();
        }
    }

    private PresignedUrlDownloadRequest createPartRequest(int partIndex) {
        // Calculate byte range for this part
        long startByte = partIndex * partSizeInBytes;
        long endByte = Math.min(((partIndex + 1) * partSizeInBytes) - 1, totalContentLength - 1);

        String rangeHeader = String.format("%s%d-%d", BYTES_RANGE_PREFIX, startByte, endByte);

        return baseRequest.toBuilder()
                .range(rangeHeader)
                .build();
    }
}