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

import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;

/**
 * Represents a completed presigned URL download to a file.
 */
@SdkPublicApi
public final class CompletedPresignedUrlFileDownload implements CompletedObjectTransfer {

    private final GetObjectResponse response;

    private CompletedPresignedUrlFileDownload(Builder builder) {
        this.response = Validate.paramNotNull(builder.response, "response");
    }

    /**
     * Creates a builder that can be used to create a {@link CompletedPresignedUrlFileDownload}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the {@link GetObjectResponse} from the completed download
     */
    public GetObjectResponse response() {
        return response;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        CompletedPresignedUrlFileDownload that = (CompletedPresignedUrlFileDownload) o;
        return response.equals(that.response);
    }

    @Override
    public int hashCode() {
        return response.hashCode();
    }

    @Override
    public String toString() {
        return ToString.builder("CompletedPresignedUrlFileDownload")
                       .add("response", response)
                       .build();
    }

    public static final class Builder {
        private GetObjectResponse response;

        private Builder() {
        }

        /**
         * Sets the {@link GetObjectResponse} from the completed download.
         *
         * @param response the response
         * @return this builder for method chaining
         */
        public Builder response(GetObjectResponse response) {
            this.response = response;
            return this;
        }

        /**
         * Builds a {@link CompletedPresignedUrlFileDownload} based on the properties supplied to this builder.
         *
         * @return An initialized {@link CompletedPresignedUrlFileDownload}
         */
        public CompletedPresignedUrlFileDownload build() {
            return new CompletedPresignedUrlFileDownload(this);
        }
    }
}