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

import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * An opaque token that holds the state and can be used to resume a paused presigned URL download operation.
 * <p>
 * <b>Important:</b> Presigned URLs have expiration times. If the URL expires before resume, 
 * the resume operation will fail. This token does not regenerate URLs.
 *
 * @see S3TransferManager#downloadFile(PresignedDownloadFileRequest)
 */
@SdkPublicApi
public final class ResumablePresignedDownload implements ResumableTransfer,
                                                         ToCopyableBuilder<ResumablePresignedDownload.Builder, ResumablePresignedDownload> {

    private final PresignedDownloadFileRequest originalRequest;
    private final Instant urlExpirationTime;
    private final long bytesTransferred;
    private final String s3ObjectEtag;
    private final Long totalSizeInBytes;
    private final Instant fileLastModified;
    private final List<CompletedRange> completedRanges;

    private ResumablePresignedDownload(DefaultBuilder builder) {
        this.originalRequest = Validate.paramNotNull(builder.originalRequest, "originalRequest");
        this.urlExpirationTime = builder.urlExpirationTime;
        this.bytesTransferred = builder.bytesTransferred == null ? 0 : Validate.isNotNegative(builder.bytesTransferred, "bytesTransferred");
        this.s3ObjectEtag = builder.s3ObjectEtag;
        this.totalSizeInBytes = Validate.isPositiveOrNull(builder.totalSizeInBytes, "totalSizeInBytes");
        this.fileLastModified = builder.fileLastModified;
        this.completedRanges = builder.completedRanges;
    }

    public static Builder builder() {
        return new DefaultBuilder();
    }

    /**
     * @return the original {@link PresignedDownloadFileRequest}
     */
    public PresignedDownloadFileRequest originalRequest() {
        return originalRequest;
    }

    /**
     * @return the expiration time of the presigned URL, if known
     */
    public Optional<Instant> urlExpirationTime() {
        return Optional.ofNullable(urlExpirationTime);
    }

    /**
     * Checks if the presigned URL has expired
     * @return true if URL is expired, false if still valid or expiration unknown
     */
    public boolean isUrlExpired() {
        return urlExpirationTime != null && Instant.now().isAfter(urlExpirationTime);
    }

    /**
     * Validates that the URL is still valid for resume operations
     * @throws SdkClientException if URL has expired
     */
    public void validateUrlNotExpired() {
        if (isUrlExpired()) {
            throw SdkClientException.create("Cannot resume download: presigned URL expired at " + urlExpirationTime);
        }
    }

    public long bytesTransferred() {
        return bytesTransferred;
    }

    public Optional<String> s3ObjectEtag() {
        return Optional.ofNullable(s3ObjectEtag);
    }

    public OptionalLong totalSizeInBytes() {
        return totalSizeInBytes == null ? OptionalLong.empty() : OptionalLong.of(totalSizeInBytes);
    }

    public Instant fileLastModified() {
        return fileLastModified;
    }

    /**
     * @return completed byte ranges for multipart presigned downloads
     */
    public List<CompletedRange> completedRanges() {
        return completedRanges;
    }

    @Override
    public void serializeToFile(Path path) {
        throw new UnsupportedOperationException("Presigned URL downloads cannot be serialized due to URL expiration risk");
    }

    @Override
    public void serializeToOutputStream(OutputStream outputStream) {
        throw new UnsupportedOperationException("Presigned URL downloads cannot be serialized due to URL expiration risk");
    }

    @Override
    public String toString() {
        return ToString.builder("ResumablePresignedDownload")
                       .add("bytesTransferred", bytesTransferred)
                       .add("fileLastModified", fileLastModified)
                       .add("s3ObjectEtag", s3ObjectEtag)
                       .add("totalSizeInBytes", totalSizeInBytes)
                       .add("urlExpirationTime", urlExpirationTime)
                       .add("isExpired", isUrlExpired())
                       .add("completedRanges", completedRanges != null ? completedRanges.size() : 0)
                       .build();
    }

    /**
     * Represents a completed byte range in a multipart presigned download
     */
    public static class CompletedRange {
        private final long startByte;
        private final long endByte;

        public CompletedRange(long startByte, long endByte) {
            this.startByte = startByte;
            this.endByte = endByte;
        }

        public long startByte() { return startByte; }
        public long endByte() { return endByte; }
        public long size() { return endByte - startByte + 1; }
    }

    public interface Builder extends CopyableBuilder<Builder, ResumablePresignedDownload> {
        Builder originalRequest(PresignedDownloadFileRequest originalRequest);
        Builder urlExpirationTime(Instant urlExpirationTime);
        Builder bytesTransferred(Long bytesTransferred);
        Builder s3ObjectEtag(String s3ObjectEtag);
        Builder totalSizeInBytes(Long totalSizeInBytes);
        Builder fileLastModified(Instant fileLastModified);
        Builder completedRanges(List<CompletedRange> completedRanges);
    }

    private static final class DefaultBuilder implements Builder {
        private PresignedDownloadFileRequest originalRequest;
        private Instant urlExpirationTime;
        private Long bytesTransferred;
        private String s3ObjectEtag;
        private Long totalSizeInBytes;
        private Instant fileLastModified;
        private List<CompletedRange> completedRanges;

        @Override
        public Builder originalRequest(PresignedDownloadFileRequest originalRequest) {
            this.originalRequest = originalRequest;
            return this;
        }

        @Override
        public Builder urlExpirationTime(Instant urlExpirationTime) {
            this.urlExpirationTime = urlExpirationTime;
            return this;
        }

        @Override
        public Builder bytesTransferred(Long bytesTransferred) {
            this.bytesTransferred = bytesTransferred;
            return this;
        }

        @Override
        public Builder s3ObjectEtag(String s3ObjectEtag) {
            this.s3ObjectEtag = s3ObjectEtag;
            return this;
        }

        @Override
        public Builder totalSizeInBytes(Long totalSizeInBytes) {
            this.totalSizeInBytes = totalSizeInBytes;
            return this;
        }

        @Override
        public Builder fileLastModified(Instant fileLastModified) {
            this.fileLastModified = fileLastModified;
            return this;
        }

        @Override
        public Builder completedRanges(List<CompletedRange> completedRanges) {
            this.completedRanges = completedRanges;
            return this;
        }

        @Override
        public ResumablePresignedDownload build() {
            return new ResumablePresignedDownload(this);
        }
    }

    @Override
    public Builder toBuilder() {
        return new DefaultBuilder()
                .originalRequest(originalRequest)
                .urlExpirationTime(urlExpirationTime)
                .bytesTransferred(bytesTransferred)
                .s3ObjectEtag(s3ObjectEtag)
                .totalSizeInBytes(totalSizeInBytes)
                .fileLastModified(fileLastModified)
                .completedRanges(completedRanges);
    }
}