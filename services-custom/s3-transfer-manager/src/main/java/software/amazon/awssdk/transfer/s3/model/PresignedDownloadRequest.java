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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.annotations.NotThreadSafe;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * Download an object from S3 using a presigned URL to a custom destination via {@link AsyncResponseTransformer}.
 *
 * @param <T> The type of data the {@link AsyncResponseTransformer} produces
 * @see S3TransferManager#download(PresignedDownloadRequest)
 */
@SdkPublicApi
public final class PresignedDownloadRequest<T>
    implements TransferObjectRequest, ToCopyableBuilder<PresignedDownloadRequest.Builder<T>, PresignedDownloadRequest<T>> {

    private final URL presignedUrl;
    private final AsyncResponseTransformer<GetObjectResponse, T> responseTransformer;
    private final Map<String, String> additionalHeaders;
    private final List<TransferListener> transferListeners;

    private PresignedDownloadRequest(DefaultBuilder<T> builder) {
        this.presignedUrl = Validate.paramNotNull(builder.presignedUrl, "presignedUrl");
        this.responseTransformer = Validate.paramNotNull(builder.responseTransformer, "responseTransformer");
        this.additionalHeaders = builder.additionalHeaders != null ? new HashMap<>(builder.additionalHeaders) : new HashMap<>();
        this.transferListeners = builder.transferListeners != null ? new ArrayList<>(builder.transferListeners) : new ArrayList<>();
    }

    /**
     * Creates an untyped builder that can be used to create a {@link PresignedDownloadRequest}.
     */
    public static UntypedBuilder builder() {
        return new UntypedBuilder();
    }
    
    @Override
    public Builder<T> toBuilder() {
        return new DefaultBuilder<>(this);
    }

    /**
     * The presigned URL for the S3 object.
     */
    public URL presignedUrl() {
        return presignedUrl;
    }

    /**
     * The {@link AsyncResponseTransformer} that should be used to transform the response.
     */
    public AsyncResponseTransformer<GetObjectResponse, T> responseTransformer() {
        return responseTransformer;
    }

    /**
     * Additional headers to include in the request.
     */
    public Map<String, String> additionalHeaders() {
        return additionalHeaders;
    }

    /**
     * List of {@link TransferListener}s that will be notified as part of this request.
     */
    @Override
    public List<TransferListener> transferListeners() {
        return transferListeners;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PresignedDownloadRequest<?> that = (PresignedDownloadRequest<?>) o;
        return Objects.equals(presignedUrl, that.presignedUrl) &&
               Objects.equals(responseTransformer, that.responseTransformer) &&
               Objects.equals(additionalHeaders, that.additionalHeaders) &&
               Objects.equals(transferListeners, that.transferListeners);
    }

    @Override
    public int hashCode() {
        return Objects.hash(presignedUrl, responseTransformer, additionalHeaders, transferListeners);
    }

    @Override
    public String toString() {
        return ToString.builder("PresignedDownloadRequest")
                       .add("presignedUrl", presignedUrl)
                       .add("responseTransformer", responseTransformer)
                       .add("additionalHeaders", additionalHeaders)
                       .add("transferListeners", transferListeners)
                       .build();
    }

    /**
     * Untyped builder for creating {@link PresignedDownloadRequest} instances.
     */
    @SdkPublicApi
    @NotThreadSafe
    public static final class UntypedBuilder {
        private URL presignedUrl;
        private Map<String, String> additionalHeaders;
        private List<TransferListener> transferListeners;

        private UntypedBuilder() {
        }

        /**
         * The presigned URL for the S3 object.
         */
        public UntypedBuilder presignedUrl(URL presignedUrl) {
            this.presignedUrl = presignedUrl;
            return this;
        }

        /**
         * Additional headers to include in the request.
         */
        public UntypedBuilder additionalHeaders(Map<String, String> additionalHeaders) {
            this.additionalHeaders = additionalHeaders != null ? new HashMap<>(additionalHeaders) : null;
            return this;
        }

        /**
         * The {@link TransferListener}s that will be notified as part of this request.
         */
        public UntypedBuilder transferListeners(Collection<TransferListener> transferListeners) {
            this.transferListeners = transferListeners != null ? new ArrayList<>(transferListeners) : null;
            return this;
        }

        /**
         * Add a {@link TransferListener} that will be notified as part of this request.
         */
        public UntypedBuilder addTransferListener(TransferListener transferListener) {
            if (transferListeners == null) {
                transferListeners = new ArrayList<>();
            }
            transferListeners.add(transferListener);
            return this;
        }

        /**
         * The {@link AsyncResponseTransformer} that should be used to transform the response.
         */
        public <T> Builder<T> responseTransformer(AsyncResponseTransformer<GetObjectResponse, T> responseTransformer) {
            return new DefaultBuilder<T>()
                .presignedUrl(presignedUrl)
                .additionalHeaders(additionalHeaders)
                .transferListeners(transferListeners)
                .responseTransformer(responseTransformer);
        }
    }

    /**
     * A typed builder for a {@link PresignedDownloadRequest}.
     */
    @SdkPublicApi
    @NotThreadSafe
    public interface Builder<T> extends CopyableBuilder<Builder<T>, PresignedDownloadRequest<T>> {

        /**
         * The presigned URL for the S3 object.
         */
        Builder<T> presignedUrl(URL presignedUrl);

        /**
         * The {@link AsyncResponseTransformer} that should be used to transform the response.
         */
        Builder<T> responseTransformer(AsyncResponseTransformer<GetObjectResponse, T> responseTransformer);

        /**
         * Additional headers to include in the request.
         */
        Builder<T> additionalHeaders(Map<String, String> additionalHeaders);

        /**
         * The {@link TransferListener}s that will be notified as part of this request.
         */
        Builder<T> transferListeners(Collection<TransferListener> transferListeners);

        /**
         * Add a {@link TransferListener} that will be notified as part of this request.
         */
        Builder<T> addTransferListener(TransferListener transferListener);
    }

    private static final class DefaultBuilder<T> implements Builder<T> {
        private URL presignedUrl;
        private AsyncResponseTransformer<GetObjectResponse, T> responseTransformer;
        private Map<String, String> additionalHeaders;
        private List<TransferListener> transferListeners;

        private DefaultBuilder() {
        }

        private DefaultBuilder(PresignedDownloadRequest<T> request) {
            this.presignedUrl = request.presignedUrl;
            this.responseTransformer = request.responseTransformer;
            this.additionalHeaders = request.additionalHeaders;
            this.transferListeners = request.transferListeners;
        }

        @Override
        public Builder<T> presignedUrl(URL presignedUrl) {
            this.presignedUrl = presignedUrl;
            return this;
        }

        @Override
        public Builder<T> responseTransformer(AsyncResponseTransformer<GetObjectResponse, T> responseTransformer) {
            this.responseTransformer = responseTransformer;
            return this;
        }

        @Override
        public Builder<T> additionalHeaders(Map<String, String> additionalHeaders) {
            this.additionalHeaders = additionalHeaders != null ? new HashMap<>(additionalHeaders) : null;
            return this;
        }

        @Override
        public Builder<T> transferListeners(Collection<TransferListener> transferListeners) {
            this.transferListeners = transferListeners != null ? new ArrayList<>(transferListeners) : null;
            return this;
        }

        @Override
        public Builder<T> addTransferListener(TransferListener transferListener) {
            if (transferListeners == null) {
                transferListeners = new ArrayList<>();
            }
            transferListeners.add(transferListener);
            return this;
        }

        @Override
        public PresignedDownloadRequest<T> build() {
            return new PresignedDownloadRequest<>(this);
        }
    }
}