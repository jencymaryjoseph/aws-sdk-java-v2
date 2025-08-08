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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

class PresignedDownloadRequestTest {

    @Test
    void builder_minimal() throws MalformedURLException {
        URL presignedUrl = new URL("https://example.com/presigned");
        
        PresignedDownloadRequest<ResponseBytes<GetObjectResponse>> request = PresignedDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .responseTransformer(AsyncResponseTransformer.toBytes())
            .build();
        
        assertThat(request.presignedUrl()).isEqualTo(presignedUrl);
        assertThat(request.responseTransformer()).isNotNull();
        assertThat(request.additionalHeaders()).isEmpty();
        assertThat(request.transferListeners()).isEmpty();
    }

    @Test
    void builder_withAllFields() throws MalformedURLException {
        URL presignedUrl = new URL("https://example.com/presigned");
        Map<String, String> headers = new HashMap<>();
        headers.put("Custom-Header", "value");
        
        PresignedDownloadRequest<ResponseBytes<GetObjectResponse>> request = PresignedDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .responseTransformer(AsyncResponseTransformer.toBytes())
            .additionalHeaders(headers)
            .build();
        
        assertThat(request.presignedUrl()).isEqualTo(presignedUrl);
        assertThat(request.additionalHeaders()).containsEntry("Custom-Header", "value");
    }

    @Test
    void fileRequest_minimal() throws MalformedURLException {
        URL presignedUrl = new URL("https://example.com/presigned");
        
        PresignedDownloadFileRequest request = PresignedDownloadFileRequest.builder()
            .presignedUrl(presignedUrl)
            .destination(Paths.get("/tmp/test.txt"))
            .build();
        
        assertThat(request.presignedUrl()).isEqualTo(presignedUrl);
        assertThat(request.destination()).isEqualTo(Paths.get("/tmp/test.txt"));
        assertThat(request.additionalHeaders()).isEmpty();
        assertThat(request.transferListeners()).isEmpty();
    }
}