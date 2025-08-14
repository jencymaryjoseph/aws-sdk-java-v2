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
import java.util.Optional;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.transfer.s3.config.TransferRequestOverrideConfiguration;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * Request object for downloading an S3 object using a presigned URL to a local file.
 * Supports multipart downloads and pause/resume functionality.
 */
@SdkPublicApi
public final class PresignedDownloadFileRequest implements TransferObjectRequest,
        ToCopyableBuilder<PresignedDownloadFileRequest.Builder, PresignedDownloadFileRequest> {

    private final PresignedUrlDownloadRequest presignedUrlDownloadRequest;
    private final Path destination;
    private final List<TransferListener> transferListeners;
    private final TransferRequestOverrideConfiguration overrideConfiguration;

    private PresignedDownloadFileRequest(BuilderImpl builder) {
        this.presignedUrlDownloadRequest = Validate.paramNotNull(builder.presignedUrlDownloadRequest, 
                                                                "presignedUrlDownloadRequest");
        this.destination = Validate.paramNotNull(builder.destination, "destination");
        this.transferListeners = builder.transferListeners != null ? 
                new ArrayList<>(builder.transferListeners) : new ArrayList<>();
        this.overrideConfiguration = builder.overrideConfiguration;
    }

    /**
     * @return the presigned URL download request
     */
    public PresignedUrlDownloadRequest presignedUrlDownloadRequest() {
        return presignedUrlDownloadRequest;
    }

    /**
     * @return the destination file path
     */
    public Path destination() {
        return destination;
    }

    @Override
    public List<TransferListener> transferListeners() {
        return transferListeners;
    }

    public Optional<TransferRequestOverrideConfiguration> overrideConfiguration() {
        return Optional.ofNullable(overrideConfiguration);
    }

    public static Builder builder() {
        return new BuilderImpl();
    }

    @Override
    public Builder toBuilder() {
        return new BuilderImpl(this);
    }

    @Override
    public String toString() {
        return ToString.builder("PresignedDownloadFileRequest")
                      .add("presignedUrlDownloadRequest", presignedUrlDownloadRequest)
                      .add("destination", destination)
                      .add("transferListeners", transferListeners)
                      .add("overrideConfiguration", overrideConfiguration)
                      .build();
    }

    public interface Builder extends CopyableBuilder<Builder, PresignedDownloadFileRequest> {
        Builder presignedUrlDownloadRequest(PresignedUrlDownloadRequest presignedUrlDownloadRequest);
        Builder destination(Path destination);
        Builder addTransferListener(TransferListener transferListener);
        Builder transferListeners(List<TransferListener> transferListeners);
        Builder overrideConfiguration(TransferRequestOverrideConfiguration overrideConfiguration);
    }

    static final class BuilderImpl implements Builder {
        private PresignedUrlDownloadRequest presignedUrlDownloadRequest;
        private Path destination;
        private List<TransferListener> transferListeners;
        private TransferRequestOverrideConfiguration overrideConfiguration;

        BuilderImpl() {
        }

        BuilderImpl(PresignedDownloadFileRequest request) {
            this.presignedUrlDownloadRequest = request.presignedUrlDownloadRequest;
            this.destination = request.destination;
            this.transferListeners = request.transferListeners;
            this.overrideConfiguration = request.overrideConfiguration;
        }

        @Override
        public Builder presignedUrlDownloadRequest(PresignedUrlDownloadRequest presignedUrlDownloadRequest) {
            this.presignedUrlDownloadRequest = presignedUrlDownloadRequest;
            return this;
        }

        @Override
        public Builder destination(Path destination) {
            this.destination = destination;
            return this;
        }

        @Override
        public Builder addTransferListener(TransferListener transferListener) {
            if (this.transferListeners == null) {
                this.transferListeners = new ArrayList<>();
            }
            this.transferListeners.add(transferListener);
            return this;
        }

        @Override
        public Builder transferListeners(List<TransferListener> transferListeners) {
            this.transferListeners = transferListeners != null ? new ArrayList<>(transferListeners) : null;
            return this;
        }

        @Override
        public Builder overrideConfiguration(TransferRequestOverrideConfiguration overrideConfiguration) {
            this.overrideConfiguration = overrideConfiguration;
            return this;
        }

        @Override
        public PresignedDownloadFileRequest build() {
            return new PresignedDownloadFileRequest(this);
        }
    }
}