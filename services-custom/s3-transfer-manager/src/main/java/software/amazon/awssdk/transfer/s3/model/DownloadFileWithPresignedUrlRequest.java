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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlGetObjectRequest;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * Request object for downloading a file from S3 using a presigned URL.
 */
@SdkPublicApi
public final class DownloadFileWithPresignedUrlRequest 
    implements TransferObjectRequest, 
               ToCopyableBuilder<DownloadFileWithPresignedUrlRequest.Builder, 
                                DownloadFileWithPresignedUrlRequest> {

    private final PresignedUrlGetObjectRequest presignedUrlGetObjectRequest;
    private final Path destination;
    private final List<TransferListener> transferListeners;

    private DownloadFileWithPresignedUrlRequest(Builder builder) {
        this.presignedUrlGetObjectRequest = Validate.paramNotNull(builder.presignedUrlGetObjectRequest, 
                                                                  "presignedUrlGetObjectRequest");
        this.destination = Validate.paramNotNull(builder.destination, "destination");
        this.transferListeners = builder.transferListeners;
    }

    /**
     * @return The presigned URL request containing the URL and optional parameters
     */
    public PresignedUrlGetObjectRequest presignedUrlGetObjectRequest() {
        return presignedUrlGetObjectRequest;
    }

    /**
     * @return The destination path where the file will be downloaded
     */
    public Path destination() {
        return destination;
    }

    @Override
    public List<TransferListener> transferListeners() {
        return transferListeners;
    }

    /**
     * Create a builder for {@link DownloadFileWithPresignedUrlRequest}.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public String toString() {
        return ToString.builder("DownloadFileWithPresignedUrlRequest")
                       .add("presignedUrlGetObjectRequest", presignedUrlGetObjectRequest)
                       .add("destination", destination)
                       .add("transferListeners", transferListeners)
                       .build();
    }

    public static final class Builder implements CopyableBuilder<Builder, DownloadFileWithPresignedUrlRequest> {
        private PresignedUrlGetObjectRequest presignedUrlGetObjectRequest;
        private Path destination;
        private List<TransferListener> transferListeners = new ArrayList<>();

        private Builder() {
        }

        private Builder(DownloadFileWithPresignedUrlRequest request) {
            this.presignedUrlGetObjectRequest = request.presignedUrlGetObjectRequest;
            this.destination = request.destination;
            this.transferListeners = new ArrayList<>(request.transferListeners);
        }

        /**
         * Sets the presigned URL request.
         *
         * @param presignedUrlGetObjectRequest the presigned URL request
         * @return this builder for method chaining
         */
        public Builder presignedUrlGetObjectRequest(PresignedUrlGetObjectRequest presignedUrlGetObjectRequest) {
            this.presignedUrlGetObjectRequest = presignedUrlGetObjectRequest;
            return this;
        }

        /**
         * Sets the presigned URL request using a consumer.
         *
         * @param presignedUrlGetObjectRequestBuilder consumer to configure the presigned URL request
         * @return this builder for method chaining
         */
        public Builder presignedUrlGetObjectRequest(
                Consumer<PresignedUrlGetObjectRequest.Builder> presignedUrlGetObjectRequestBuilder) {
            PresignedUrlGetObjectRequest.Builder builder = PresignedUrlGetObjectRequest.builder();
            presignedUrlGetObjectRequestBuilder.accept(builder);
            return presignedUrlGetObjectRequest(builder.build());
        }

        /**
         * Sets the destination path.
         *
         * @param destination the destination path
         * @return this builder for method chaining
         */
        public Builder destination(Path destination) {
            this.destination = destination;
            return this;
        }

        /**
         * Add a {@link TransferListener} that will be notified as part of this request.
         *
         * @param transferListener the transfer listener to add
         * @return this builder for method chaining
         */
        public Builder addTransferListener(TransferListener transferListener) {
            this.transferListeners.add(transferListener);
            return this;
        }

        /**
         * Sets the transfer listeners, replacing any existing listeners.
         *
         * @param transferListeners the transfer listeners
         * @return this builder for method chaining
         */
        public Builder transferListeners(List<TransferListener> transferListeners) {
            this.transferListeners = new ArrayList<>(transferListeners);
            return this;
        }

        @Override
        public DownloadFileWithPresignedUrlRequest build() {
            return new DownloadFileWithPresignedUrlRequest(this);
        }
    }
}