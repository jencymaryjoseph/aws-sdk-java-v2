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
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.SplittingTransformerConfiguration;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.utils.CompletableFutureUtils;
import software.amazon.awssdk.utils.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.utils.Logger;

@SdkInternalApi
public class PresignedUrlDownloadHelper {
    private static final Logger log = Logger.loggerFor(PresignedUrlDownloadHelper.class);
    private static final String BYTES_RANGE_PREFIX = "bytes=";
    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");

    private final S3AsyncClient s3AsyncClient;
    private final long bufferSizeInBytes;
    private final long partSizeInBytes;
    private final long multipartDownloadThresholdInBytes;

    public PresignedUrlDownloadHelper(S3AsyncClient s3AsyncClient,
                                      long bufferSizeInBytes,
                                      long partSizeInBytes,
                                      long multipartDownloadThresholdInBytes) {
        this.s3AsyncClient = s3AsyncClient;
        this.bufferSizeInBytes = bufferSizeInBytes;
        this.partSizeInBytes = partSizeInBytes;
        this.multipartDownloadThresholdInBytes = multipartDownloadThresholdInBytes;
    }

    public <T> CompletableFuture<T> downloadObject(
            PresignedUrlDownloadRequest presignedRequest,
            AsyncResponseTransformer<GetObjectResponse, T> asyncResponseTransformer) {

        // If range is specified, do single part download
        if (presignedRequest.range() != null) {
            log.debug(() -> "Range specified in presigned URL request, performing single part download");
            return s3AsyncClient.presignedUrlExtension().getObject(presignedRequest, asyncResponseTransformer);
        }

        // First, make a HEAD-like request to get content length from Content-Range header
        CompletableFuture<ContentRangeInfo> contentRangeFuture = getContentRangeInfo(presignedRequest);

        CompletableFuture<T> returnFuture = new CompletableFuture<>();

        contentRangeFuture.whenComplete((contentRangeInfo, throwable) -> {
            if (throwable != null) {
                returnFuture.completeExceptionally(throwable);
                return;
            }

            Long totalContentLength = contentRangeInfo.totalLength;
            if (totalContentLength == null || totalContentLength <= 0) {
                log.debug(() -> "Content length not available from Content-Range header, performing single part download");
                CompletableFuture<T> singlePartFuture =
                        s3AsyncClient.presignedUrlExtension().getObject(presignedRequest, asyncResponseTransformer);
                CompletableFutureUtils.forwardResultTo(singlePartFuture, returnFuture);
                return;
            }

            // Check if content is below threshold
            if (totalContentLength < multipartDownloadThresholdInBytes) {
                log.debug(() -> String.format("Content length %d is below threshold %d, performing single part download",
                        totalContentLength, multipartDownloadThresholdInBytes));
                CompletableFuture<T> singlePartFuture =
                        s3AsyncClient.presignedUrlExtension().getObject(presignedRequest, asyncResponseTransformer);
                CompletableFutureUtils.forwardResultTo(singlePartFuture, returnFuture);
                return;
            }

            log.debug(() -> String.format("Starting multipart download for presigned URL with total content length: %d",
                    totalContentLength));
            performMultipartDownload(presignedRequest, asyncResponseTransformer, contentRangeInfo, returnFuture);
        });

        return returnFuture;
    }

    private CompletableFuture<ContentRangeInfo> getContentRangeInfo(PresignedUrlDownloadRequest request) {
        // Make a request with Range: bytes=0-0 to get Content-Range header
        PresignedUrlDownloadRequest headRequest = request.toBuilder()
                .range(BYTES_RANGE_PREFIX + "0-0")
                .build();

        return s3AsyncClient.presignedUrlExtension()
                .getObject(headRequest, AsyncResponseTransformer.toBytes())
                .thenApply(response -> {
                    GetObjectResponse getObjectResponse = response.response();
                    String contentRange = getObjectResponse.contentRange();

                    if (contentRange == null) {
                        throw SdkClientException.create("Content-Range header not found in response");
                    }

                    return parseContentRange(contentRange, getObjectResponse);
                });
    }

    private ContentRangeInfo parseContentRange(String contentRange, GetObjectResponse response) {
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(contentRange);
        if (!matcher.matches()) {
            throw SdkClientException.create("Invalid Content-Range header format: " + contentRange);
        }

        long totalLength = Long.parseLong(matcher.group(3));
        return new ContentRangeInfo(totalLength, response);
    }

    private <T> void performMultipartDownload(
            PresignedUrlDownloadRequest presignedRequest,
            AsyncResponseTransformer<GetObjectResponse, T> asyncResponseTransformer,
            ContentRangeInfo contentRangeInfo,
            CompletableFuture<T> returnFuture) {

        SplittingTransformerConfiguration splittingConfig = SplittingTransformerConfiguration.builder()
                .bufferSizeInBytes(bufferSizeInBytes)
                .build();

        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split =
                asyncResponseTransformer.split(splittingConfig);

        PresignedUrlMultipartDownloaderSubscriber subscriber =
                new PresignedUrlMultipartDownloaderSubscriber(
                        s3AsyncClient,
                        presignedRequest,
                        contentRangeInfo.totalLength,
                        partSizeInBytes);

        split.publisher().subscribe(subscriber);

        CompletableFutureUtils.forwardResultTo(split.resultFuture(), returnFuture);
        CompletableFutureUtils.forwardExceptionTo(returnFuture, split.resultFuture());
    }

    private static class ContentRangeInfo {
        final Long totalLength;
        final GetObjectResponse response;

        ContentRangeInfo(Long totalLength, GetObjectResponse response) {
            this.totalLength = totalLength;
            this.response = response;
        }
    }
}