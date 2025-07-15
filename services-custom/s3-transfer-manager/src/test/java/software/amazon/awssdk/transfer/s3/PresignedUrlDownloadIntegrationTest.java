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

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlGetObjectRequest;
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload;
import software.amazon.awssdk.transfer.s3.model.FileDownload;

class PresignedUrlDownloadIntegrationTest {

    private static final String BUCKET = "jency-test-bucket";
    private static final String KEY = "test1.txt";
    private static final Region REGION = Region.US_EAST_1;

    @Test
    void downloadFileWithPresignedUrl_simpleDownload() throws Exception {
        // Generate presigned URL
        S3Presigner presigner = S3Presigner.builder()
                                           .region(REGION)
                                           .build();

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                                                            .bucket(BUCKET)
                                                            .key(KEY)
                                                            .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                                                                        .signatureDuration(Duration.ofMinutes(10))
                                                                        .getObjectRequest(getObjectRequest)
                                                                        .build();

        PresignedGetObjectRequest presignedGetObjectRequest = presigner.presignGetObject(presignRequest);
        URL presignedUrl = presignedGetObjectRequest.url();

        System.out.println("Generated presigned URL: " + presignedUrl);

        // Create Transfer Manager
        S3TransferManager transferManager = S3TransferManager.create();

        // Create presigned URL request
        PresignedUrlGetObjectRequest presignedUrlRequest = PresignedUrlGetObjectRequest.builder()
                                                                                       .presignedUrl(presignedUrl)
                                                                                       .build();

        // Download file
        Path destination = Paths.get("downloaded-test1.txt");
        
        try {
            FileDownload download = transferManager.downloadFileWithPresignedUrl(presignedUrlRequest, destination);

            System.out.println("Starting download...");
            
            // Wait for completion
            CompletedFileDownload result = download.completionFuture().join();
            
            System.out.println("Download completed!");
            System.out.println("Response: " + result.response());
            
            // Verify file exists and has content
            if (Files.exists(destination)) {
                String content = Files.readString(destination);
                System.out.println("Downloaded content: " + content);
            } else {
                System.out.println("ERROR: Downloaded file does not exist!");
            }
            
        } finally {
            // Cleanup
            Files.deleteIfExists(destination);
            transferManager.close();
            presigner.close();
        }
    }
}