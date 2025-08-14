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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.transfer.s3.config.TransferRequestOverrideConfiguration;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * Request object for downloading an S3 object using a presigned URL through a custom response transformer.
 * 
 * @param <ResultT> The type of data the {@link AsyncResponseTransformer} produces
 */
@SdkPublicApi
public final class PresignedDownloadRequest<ResultT> implements TransferObjectRequest,
        ToCopyableBuilder<PresignedDownloadRequest.Builder<ResultT>, PresignedDownloadRequest<ResultT>> {

    private final PresignedUrlDownloadRequest presignedUrlDownloadRequest;
    private final AsyncResponseTransformer<GetObjectResponse, ResultT> responseTransformer;
    private final List<TransferListener> transferListeners;
    private final TransferRequestOverrideConfiguration overrideConfiguration;

    private PresignedDownloadRequest(BuilderImpl<ResultT> builder) {
        this.presignedUrlDownloadRequest = Validate.paramNotNull(builder.presignedUrlDownloadRequest, 
                                                                "presignedUrlDownloadRequest");
        this.responseTransformer = Validate.paramNotNull(builder.responseTransformer, "responseTransformer");
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
     * @return the response transformer
     */
    public AsyncResponseTransformer<GetObjectResponse, ResultT> responseTransformer() {
        return responseTransformer;
    }

    @Override
    public List<TransferListener> transferListeners() {
        return transferListeners;
    }

    public Optional<TransferRequestOverrideConfiguration> overrideConfiguration() {
        return Optional.ofNullable(overrideConfiguration);
    }

    public static <ResultT> Builder<ResultT> builder() {
        return new BuilderImpl<>();
    }

    @Override
    public Builder<ResultT> toBuilder() {
        return new BuilderImpl<>(this);
    }

    @Override
    public String toString() {
        return ToString.builder("PresignedDownloadRequest")
                      .add("presignedUrlDownloadRequest", presignedUrlDownloadRequest)
                      .add("responseTransformer", responseTransformer)
                      .add("transferListeners", transferListeners)
                      .add("overrideConfiguration", overrideConfiguration)
                      .build();
    }

    public interface Builder<ResultT> extends CopyableBuilder<Builder<ResultT>, PresignedDownloadRequest<ResultT>> {
        Builder<ResultT> presignedUrlDownloadRequest(PresignedUrlDownloadRequest presignedUrlDownloadRequest);
        Builder<ResultT> responseTransformer(AsyncResponseTransformer<GetObjectResponse, ResultT> responseTransformer);
        Builder<ResultT> addTransferListener(TransferListener transferListener);
        Builder<ResultT> transferListeners(List<TransferListener> transferListeners);
        Builder<ResultT> overrideConfiguration(TransferRequestOverrideConfiguration overrideConfiguration);
    }

    static final class BuilderImpl<ResultT> implements Builder<ResultT> {
        private PresignedUrlDownloadRequest presignedUrlDownloadRequest;
        private AsyncResponseTransformer<GetObjectResponse, ResultT> responseTransformer;
        private List<TransferListener> transferListeners;
        private TransferRequestOverrideConfiguration overrideConfiguration;

        BuilderImpl() {
        }

        BuilderImpl(PresignedDownloadRequest<ResultT> request) {
            this.presignedUrlDownloadRequest = request.presignedUrlDownloadRequest;
            this.responseTransformer = request.responseTransformer;
            this.transferListeners = request.transferListeners;
            this.overrideConfiguration = request.overrideConfiguration;
        }

        @Override
        public Builder<ResultT> presignedUrlDownloadRequest(PresignedUrlDownloadRequest presignedUrlDownloadRequest) {
            this.presignedUrlDownloadRequest = presignedUrlDownloadRequest;
            return this;
        }

        @Override
        public Builder<ResultT> responseTransformer(AsyncResponseTransformer<GetObjectResponse, ResultT> responseTransformer) {
            this.responseTransformer = responseTransformer;
            return this;
        }

        @Override
        public Builder<ResultT> addTransferListener(TransferListener transferListener) {
            if (this.transferListeners == null) {
                this.transferListeners = new ArrayList<>();
            }
            this.transferListeners.add(transferListener);
            return this;
        }

        @Override
        public Builder<ResultT> transferListeners(List<TransferListener> transferListeners) {
            this.transferListeners = transferListeners != null ? new ArrayList<>(transferListeners) : null;
            return this;
        }

        @Override
        public Builder<ResultT> overrideConfiguration(TransferRequestOverrideConfiguration overrideConfiguration) {
            this.overrideConfiguration = overrideConfiguration;
            return this;
        }

        @Override
        public PresignedDownloadRequest<ResultT> build() {
            return new PresignedDownloadRequest<>(this);
        }
    }
}