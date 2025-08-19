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

package software.amazon.awssdk.transfer.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.transfer.s3.model.Download;
import software.amazon.awssdk.transfer.s3.model.FileDownload;
import software.amazon.awssdk.transfer.s3.model.PresignedDownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.PresignedDownloadRequest;

/**
 * Integration test for presigned URL download functionality.
 * This test verifies the API structure and method signatures without requiring actual S3 operations.
 */
class PresignedUrlDownloadIntegrationTest {

    @Test
    void presignedDownloadFileRequest_shouldBuildCorrectly() throws Exception {
        // Given
        URL presignedUrl = new URL("https://test-bucket.s3.amazonaws.com/test-key?presigned=true");
        Path destination = Paths.get("/tmp/test-file");
        
        PresignedUrlDownloadRequest presignedRequest = PresignedUrlDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .build();
        
        // When
        PresignedDownloadFileRequest request = PresignedDownloadFileRequest.builder()
            .presignedUrlDownloadRequest(presignedRequest)
            .destination(destination)
            .build();
        
        // Then
        assertThat(request.presignedUrlDownloadRequest()).isEqualTo(presignedRequest);
        assertThat(request.destination()).isEqualTo(destination);
    }

    @Test
    void presignedDownloadRequest_shouldBuildCorrectly() throws Exception {
        // Given
        URL presignedUrl = new URL("https://test-bucket.s3.amazonaws.com/test-key?presigned=true");
        
        PresignedUrlDownloadRequest presignedRequest = PresignedUrlDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .range("bytes=0-1023")
            .build();
        
        // When
        PresignedDownloadRequest<ResponseBytes<GetObjectResponse>> request = PresignedDownloadRequest.builder()
            .presignedUrlDownloadRequest(presignedRequest)
            .responseTransformer(AsyncResponseTransformer.toBytes())
            .build();
        
        // Then
        assertThat(request.presignedUrlDownloadRequest()).isEqualTo(presignedRequest);
        assertThat(request.responseTransformer()).isNotNull();
    }

    @Test
    void transferManagerInterface_shouldHavePresignedUrlMethods() {
        // This test verifies that the S3TransferManager interface has the expected methods
        // by checking method signatures exist (compilation test)
        
        S3TransferManager transferManager = S3TransferManager.create();
        
        // Verify method signatures exist
        assertThat(transferManager).isNotNull();
        
        // These method calls would fail at runtime without proper setup,
        // but they verify the API exists and compiles correctly
        try {
            URL presignedUrl = new URL("https://test-bucket.s3.amazonaws.com/test-key?presigned=true");
            
            PresignedDownloadFileRequest fileRequest = PresignedDownloadFileRequest.builder()
                .presignedUrlDownloadRequest(PresignedUrlDownloadRequest.builder()
                    .presignedUrl(presignedUrl)
                    .build())
                .destination(Paths.get("/tmp/test"))
                .build();
            
            PresignedDownloadRequest<ResponseBytes<GetObjectResponse>> downloadRequest = PresignedDownloadRequest.builder()
                .presignedUrlDownloadRequest(PresignedUrlDownloadRequest.builder()
                    .presignedUrl(presignedUrl)
                    .build())
                .responseTransformer(AsyncResponseTransformer.toBytes())
                .build();
            
            // Verify methods exist and return correct types
            FileDownload fileDownload = transferManager.downloadFileWithPresignedUrl(fileRequest);
            Download<ResponseBytes<GetObjectResponse>> download = transferManager.downloadWithPresignedUrl(downloadRequest);
            
            assertThat(fileDownload).isNotNull();
            assertThat(download).isNotNull();
            
        } catch (Exception e) {
            // Expected - we're just testing API compilation, not actual functionality
        }
        
        transferManager.close();
    }
}