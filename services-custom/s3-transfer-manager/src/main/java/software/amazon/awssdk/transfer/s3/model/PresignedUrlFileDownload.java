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

package software.amazon.awssdk.transfer.s3.model;

import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.transfer.s3.progress.TransferProgress;

/**
 * Represents a presigned URL download to a file that has been initiated.
 *
 * <p>
 * Note: the {@link CompletableFuture} returned by {@link #completionFuture()} will only be completed successfully if the
 * entire object is downloaded. It will be completed exceptionally if the download fails.
 */
@SdkPublicApi
public interface PresignedUrlFileDownload extends ObjectTransfer {

    /**
     * Returns a {@link CompletableFuture} that will be completed when the entire object has been downloaded to the file.
     *
     * @return A {@link CompletableFuture} that will be completed when the entire object has been downloaded.
     */
    CompletableFuture<CompletedPresignedUrlFileDownload> completionFuture();

    /**
     * Returns the current progress of the transfer.
     *
     * @return the current progress of the transfer
     */
    TransferProgress progress();

    /**
     * Pause the download. Note that not all downloads can be paused. If the download cannot be paused, the returned
     * {@link CompletableFuture} will be completed exceptionally.
     *
     * @return A {@link CompletableFuture} that will be completed when the download has been paused.
     */
    default CompletableFuture<Void> pause() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(new UnsupportedOperationException("Presigned URL downloads do not support pause"));
        return future;
    }
}