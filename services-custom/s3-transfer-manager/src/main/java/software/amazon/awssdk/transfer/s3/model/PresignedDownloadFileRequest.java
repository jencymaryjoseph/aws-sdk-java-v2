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

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.annotations.NotThreadSafe;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * Download an object from S3 using a presigned URL to a local file.
 *
 * @see S3TransferManager#downloadFile(PresignedDownloadFileRequest)
 */
@SdkPublicApi
public final class PresignedDownloadFileRequest
    implements TransferObjectRequest, ToCopyableBuilder<PresignedDownloadFileRequest.Builder, PresignedDownloadFileRequest> {

    private final URL presignedUrl;
    private final Path destination;
    private final Map<String, String> additionalHeaders;
    private final List<TransferListener> transferListeners;

    private PresignedDownloadFileRequest(DefaultBuilder builder) {
        this.presignedUrl = Validate.paramNotNull(builder.presignedUrl, "presignedUrl");
        this.destination = Validate.paramNotNull(builder.destination, "destination");
        this.additionalHeaders = builder.additionalHeaders != null ? new HashMap<>(builder.additionalHeaders) : new HashMap<>();
        this.transferListeners = builder.transferListeners != null ? new ArrayList<>(builder.transferListeners) : new ArrayList<>();
    }

    /**
     * Creates a builder that can be used to create a {@link PresignedDownloadFileRequest}.
     */
    public static Builder builder() {
        return new DefaultBuilder();
    }
    
    @Override
    public Builder toBuilder() {
        return new DefaultBuilder(this);
    }

    /**
     * The presigned URL for the S3 object.
     */
    public URL presignedUrl() {
        return presignedUrl;
    }

    /**
     * The {@link Path} to file that response contents will be written to.
     */
    public Path destination() {
        return destination;
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
        PresignedDownloadFileRequest that = (PresignedDownloadFileRequest) o;
        return Objects.equals(presignedUrl, that.presignedUrl) &&
               Objects.equals(destination, that.destination) &&
               Objects.equals(additionalHeaders, that.additionalHeaders) &&
               Objects.equals(transferListeners, that.transferListeners);
    }

    @Override
    public int hashCode() {
        return Objects.hash(presignedUrl, destination, additionalHeaders, transferListeners);
    }

    @Override
    public String toString() {
        return ToString.builder("PresignedDownloadFileRequest")
                       .add("presignedUrl", presignedUrl)
                       .add("destination", destination)
                       .add("additionalHeaders", additionalHeaders)
                       .add("transferListeners", transferListeners)
                       .build();
    }

    /**
     * A builder for a {@link PresignedDownloadFileRequest}, created with {@link #builder()}
     */
    @SdkPublicApi
    @NotThreadSafe
    public interface Builder extends CopyableBuilder<Builder, PresignedDownloadFileRequest> {

        /**
         * The presigned URL for the S3 object.
         */
        Builder presignedUrl(URL presignedUrl);

        /**
         * The {@link Path} to file that response contents will be written to.
         */
        Builder destination(Path destination);

        /**
         * The file that response contents will be written to.
         */
        default Builder destination(File destination) {
            Validate.paramNotNull(destination, "destination");
            return destination(destination.toPath());
        }

        /**
         * Additional headers to include in the request.
         */
        Builder additionalHeaders(Map<String, String> additionalHeaders);

        /**
         * The {@link TransferListener}s that will be notified as part of this request.
         */
        Builder transferListeners(Collection<TransferListener> transferListeners);

        /**
         * Add a {@link TransferListener} that will be notified as part of this request.
         */
        Builder addTransferListener(TransferListener transferListener);
    }

    private static final class DefaultBuilder implements Builder {
        private URL presignedUrl;
        private Path destination;
        private Map<String, String> additionalHeaders;
        private List<TransferListener> transferListeners;

        private DefaultBuilder() {
        }

        private DefaultBuilder(PresignedDownloadFileRequest request) {
            this.presignedUrl = request.presignedUrl;
            this.destination = request.destination;
            this.additionalHeaders = request.additionalHeaders;
            this.transferListeners = request.transferListeners;
        }

        @Override
        public Builder presignedUrl(URL presignedUrl) {
            this.presignedUrl = presignedUrl;
            return this;
        }

        @Override
        public Builder destination(Path destination) {
            this.destination = destination;
            return this;
        }

        @Override
        public Builder additionalHeaders(Map<String, String> additionalHeaders) {
            this.additionalHeaders = additionalHeaders != null ? new HashMap<>(additionalHeaders) : null;
            return this;
        }

        @Override
        public Builder transferListeners(Collection<TransferListener> transferListeners) {
            this.transferListeners = transferListeners != null ? new ArrayList<>(transferListeners) : null;
            return this;
        }

        @Override
        public Builder addTransferListener(TransferListener transferListener) {
            if (transferListeners == null) {
                transferListeners = new ArrayList<>();
            }
            transferListeners.add(transferListener);
            return this;
        }

        @Override
        public PresignedDownloadFileRequest build() {
            return new PresignedDownloadFileRequest(this);
        }
    }
}