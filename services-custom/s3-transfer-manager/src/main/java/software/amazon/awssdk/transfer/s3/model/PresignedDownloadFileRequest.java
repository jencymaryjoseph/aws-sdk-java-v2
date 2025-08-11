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
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

@SdkPublicApi
public final class PresignedDownloadFileRequest
    implements TransferObjectRequest, ToCopyableBuilder<PresignedDownloadFileRequest.Builder, PresignedDownloadFileRequest> {
    
    private final PresignedUrlDownloadRequest presignedUrlDownloadRequest;
    private final Path destination;
    private final List<TransferListener> transferListeners;

    private PresignedDownloadFileRequest(Builder builder) {
        this.presignedUrlDownloadRequest = builder.presignedUrlDownloadRequest;
        this.destination = builder.destination;
        this.transferListeners = builder.transferListeners != null ? 
            new ArrayList<>(builder.transferListeners) : new ArrayList<>();
    }

    public PresignedUrlDownloadRequest presignedUrlDownloadRequest() {
        return presignedUrlDownloadRequest;
    }

    public Path destination() {
        return destination;
    }



    public List<TransferListener> transferListeners() {
        return transferListeners;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder implements CopyableBuilder<Builder, PresignedDownloadFileRequest> {
        private PresignedUrlDownloadRequest presignedUrlDownloadRequest;
        private Path destination;
        private List<TransferListener> transferListeners;

        private Builder() {
        }

        private Builder(PresignedDownloadFileRequest request) {
            this.presignedUrlDownloadRequest = request.presignedUrlDownloadRequest;
            this.destination = request.destination;
            this.transferListeners = request.transferListeners != null ? 
                new ArrayList<>(request.transferListeners) : null;
        }

        public Builder presignedUrlDownloadRequest(PresignedUrlDownloadRequest presignedUrlDownloadRequest) {
            this.presignedUrlDownloadRequest = presignedUrlDownloadRequest;
            return this;
        }

        public Builder destination(Path destination) {
            this.destination = destination;
            return this;
        }



        public Builder addTransferListener(TransferListener transferListener) {
            if (this.transferListeners == null) {
                this.transferListeners = new ArrayList<>();
            }
            this.transferListeners.add(transferListener);
            return this;
        }

        @Override
        public PresignedDownloadFileRequest build() {
            return new PresignedDownloadFileRequest(this);
        }
    }
}