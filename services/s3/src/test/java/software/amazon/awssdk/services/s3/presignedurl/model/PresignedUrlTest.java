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

package software.amazon.awssdk.services.s3.presignedurl.model;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlGetObjectRequest;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;


public class PresignedUrlTest {
    @Test
    void presignedUrlGetTest(@TempDir Path tempDir) throws IOException {
        // Generate a presigned URL
        S3Presigner presigner = S3Presigner.builder()
                                           .build();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                                                            .bucket("jency-test-bucket")
                                                            .key("test1.txt")
                                                            .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                                                                        .signatureDuration(java.time.Duration.ofDays(5))
                                                                        .getObjectRequest(getObjectRequest)
                                                                        .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        URL presignedUrl = presignedRequest.url();

        S3Client s3Client = S3Client.builder()
            .region(Region.US_WEST_2)
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .build();

        PresignedUrlGetObjectRequest request = PresignedUrlGetObjectRequest.builder()
                                                                           .presignedUrl(presignedUrl)
                                                                           .range("bytes=0-5")
                                                                           .build();

        // Test 1: getObject with ResponseTransformer.toBytes()
        System.out.println("\n--- Testing getObject with ResponseTransformer.toBytes() ---");
        ResponseBytes<GetObjectResponse> response = s3Client
            .presignedUrlManager()
            .getObject(request, ResponseTransformer.toBytes());

        System.out.println("Content range: " + response.response().contentRange());
        
        // Test 2: getObject with ResponseInputStream
        System.out.println("\n--- Testing getObject returning ResponseInputStream ---");
        ResponseInputStream<GetObjectResponse> streamResponse = s3Client
            .presignedUrlManager()
            .getObject(request);
        
        byte[] buffer = new byte[1024];
        int bytesRead = streamResponse.read(buffer);
        System.out.println("Read " + bytesRead + " bytes from stream");
        System.out.println("Content: " + new String(buffer, 0, bytesRead));
        System.out.println("Content range: " + streamResponse.response().contentRange());
        streamResponse.close();
        
        // Test 3: getObject with file download
        System.out.println("\n--- Testing getObject with file download ---");
        Path downloadPath = tempDir.resolve("downloaded-file.txt");
        GetObjectResponse fileResponse = s3Client
            .presignedUrlManager()
            .getObject(request, downloadPath);
        
        System.out.println("File downloaded to: " + downloadPath);
        System.out.println("File content: " + new String(Files.readAllBytes(downloadPath)));
        System.out.println("Content range: " + fileResponse.contentRange());
        
        // Test 4: getObjectAsBytes
        System.out.println("\n--- Testing getObjectAsBytes ---");
        ResponseBytes<GetObjectResponse> bytesResponse2 = s3Client
            .presignedUrlManager()
            .getObjectAsBytes(request);
        
        System.out.println("Content range: " + bytesResponse2.response().contentRange());
        System.out.println("Content: " + new String(bytesResponse2.asByteArray()));
        
        // Test 5: Custom transformer
        System.out.println("\n--- Testing custom transformer ---");
        Path customPath = tempDir.resolve("custom-transformer-file.txt");
        
        // Create a custom transformer that counts bytes while downloading to a file
        ResponseTransformer<GetObjectResponse, Long> countingTransformer = new ResponseTransformer<GetObjectResponse, Long>() {
            @Override
            public Long transform(GetObjectResponse response, AbortableInputStream inputStream) throws Exception {
                // Copy the content to a file
                Files.copy(inputStream, customPath);
                
                // Get the file size
                long fileSize = Files.size(customPath);
                
                System.out.println("Downloaded " + fileSize + " bytes");
                System.out.println("File content: " + new String(Files.readAllBytes(customPath)));
                System.out.println("Content type: " + response.contentType());
                System.out.println("ETag: " + response.eTag());
                
                // Return the file size
                return fileSize;
            }
        };

        Long bytesDownloaded = s3Client.presignedUrlManager()
                                      .getObject(request, countingTransformer);
        
        System.out.println("Bytes downloaded: " + bytesDownloaded);
        
        // Test 6: Consumer builder pattern
        System.out.println("\n--- Testing consumer builder pattern ---");
        
        // Create a request without range to get the full object
        ResponseBytes<GetObjectResponse> consumerResponse = s3Client.presignedUrlManager()
            .getObject(b -> b.presignedUrl(presignedUrl), ResponseTransformer.toBytes());
        
        System.out.println("Consumer builder response content length: " + consumerResponse.response().contentLength());
        System.out.println("Consumer builder response content type: " + consumerResponse.response().contentType());
        System.out.println("Consumer builder response content: " + new String(consumerResponse.asByteArray()));
        
        // Test 7: Consumer builder with file download
        System.out.println("\n--- Testing consumer builder with file download ---");
        Path consumerFilePath = tempDir.resolve("consumer-file.txt");
        GetObjectResponse consumerFileResponse = s3Client.presignedUrlManager()
            .getObject(b -> b.presignedUrl(presignedUrl), consumerFilePath);
        
        System.out.println("File downloaded to: " + consumerFilePath);
        System.out.println("File content: " + new String(Files.readAllBytes(consumerFilePath)));
        System.out.println("Content type: " + consumerFileResponse.contentType());
        
        // Test 8: Consumer builder with getObjectAsBytes
        System.out.println("\n--- Testing consumer builder with getObjectAsBytes ---");
        ResponseBytes<GetObjectResponse> consumerBytesResponse = s3Client.presignedUrlManager()
            .getObjectAsBytes(b -> b.presignedUrl(presignedUrl));
        
        System.out.println("Content length: " + consumerBytesResponse.response().contentLength());
        System.out.println("Content: " + new String(consumerBytesResponse.asByteArray()));
        
        // Test 9: Consumer builder with ResponseInputStream
        System.out.println("\n--- Testing consumer builder with ResponseInputStream ---");
        ResponseInputStream<GetObjectResponse> consumerStreamResponse = s3Client.presignedUrlManager()
            .getObject(b -> b.presignedUrl(presignedUrl));
        
        byte[] consumerBuffer = new byte[1024];
        int consumerBytesRead = consumerStreamResponse.read(consumerBuffer);
        System.out.println("Read " + consumerBytesRead + " bytes from stream");
        System.out.println("Content: " + new String(consumerBuffer, 0, consumerBytesRead));
        System.out.println("Content type: " + consumerStreamResponse.response().contentType());
        consumerStreamResponse.close();
    }
}
