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

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

@SdkPublicApi
public final class PresignedDownloadRequest<T> 
        implements TransferObjectRequest, ToCopyableBuilder<PresignedDownloadRequest.Builder<T>, PresignedDownloadRequest<T>> {
    
    private final URL presignedUrl;
    private final AsyncResponseTransformer<GetObjectResponse, T> responseTransformer;
    private final Map<String, String> additionalHeaders;
    private final List<TransferListener> transferListeners;

    private PresignedDownloadRequest(Builder<T> builder) {
        this.presignedUrl = builder.presignedUrl;
        this.responseTransformer = builder.responseTransformer;
        this.additionalHeaders = builder.additionalHeaders != null ? 
            new HashMap<>(builder.additionalHeaders) : new HashMap<>();
        this.transferListeners = builder.transferListeners != null ? 
            new ArrayList<>(builder.transferListeners) : new ArrayList<>();
    }

    public URL presignedUrl() {
        return presignedUrl;
    }

    public AsyncResponseTransformer<GetObjectResponse, T> responseTransformer() {
        return responseTransformer;
    }

    public Map<String, String> additionalHeaders() {
        return additionalHeaders;
    }

    public List<TransferListener> transferListeners() {
        return transferListeners;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    @Override
    public Builder<T> toBuilder() {
        return new Builder<>(this);
    }

    public static final class Builder<T> implements CopyableBuilder<Builder<T>, PresignedDownloadRequest<T>> {
        private URL presignedUrl;
        private AsyncResponseTransformer<GetObjectResponse, T> responseTransformer;
        private Map<String, String> additionalHeaders;
        private List<TransferListener> transferListeners;

        private Builder() {
        }

        private Builder(PresignedDownloadRequest<T> request) {
            this.presignedUrl = request.presignedUrl;
            this.responseTransformer = request.responseTransformer;
            this.additionalHeaders = request.additionalHeaders != null ? 
                new HashMap<>(request.additionalHeaders) : null;
            this.transferListeners = request.transferListeners != null ? 
                new ArrayList<>(request.transferListeners) : null;
        }

        public Builder<T> presignedUrl(URL presignedUrl) {
            this.presignedUrl = presignedUrl;
            return this;
        }

        public Builder<T> responseTransformer(AsyncResponseTransformer<GetObjectResponse, T> responseTransformer) {
            this.responseTransformer = responseTransformer;
            return this;
        }

        public Builder<T> additionalHeaders(Map<String, String> additionalHeaders) {
            this.additionalHeaders = additionalHeaders != null ? new HashMap<>(additionalHeaders) : null;
            return this;
        }

        public Builder<T> addTransferListener(TransferListener transferListener) {
            if (this.transferListeners == null) {
                this.transferListeners = new ArrayList<>();
            }
            this.transferListeners.add(transferListener);
            return this;
        }

        @Override
        public PresignedDownloadRequest<T> build() {
            return new PresignedDownloadRequest<>(this);
        }
    }
}