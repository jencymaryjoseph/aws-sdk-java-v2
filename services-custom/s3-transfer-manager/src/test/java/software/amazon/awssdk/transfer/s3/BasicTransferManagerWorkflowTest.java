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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.internal.multipart.MultipartS3AsyncClient;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;
import software.amazon.awssdk.transfer.s3.model.CompletedDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload;
import software.amazon.awssdk.transfer.s3.model.Download;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.DownloadRequest;
import software.amazon.awssdk.transfer.s3.model.FileDownload;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;
import software.amazon.awssdk.transfer.s3.model.PresignedDownloadFileRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import java.time.Duration;

public class BasicTransferManagerWorkflowTest {

    private static final Region TEST_REGION = Region.US_EAST_2; // Changed to match bucket region
    private static final String TEST_BUCKET = "jency-test-bucket"; // Update with your bucket
    
    private S3Client s3Client;
    private S3AsyncClient s3AsyncClient;
    private S3TransferManager transferManager;
    private String testKey;

    @BeforeEach
    void setUp() {
        s3Client = S3Client.builder().region(TEST_REGION).build();
        s3AsyncClient = S3AsyncClient.builder().region(TEST_REGION).build();
        
        // Create Transfer Manager with MultipartS3AsyncClient
        MultipartConfiguration config = MultipartConfiguration.builder()
                .minimumPartSizeInBytes(8 * 1024 * 1024L) // 8MB
                .build();
        S3AsyncClient multipartClient = MultipartS3AsyncClient.create(s3AsyncClient, config, true);
        
        transferManager = S3TransferManager.builder()
                .s3Client(multipartClient)
                .build();
        
        testKey = "Amazon Q.dmg" + System.currentTimeMillis();
    }

    @AfterEach
    void tearDown() {
        try {
            if (transferManager != null) {
                transferManager.close();
            }
            if (s3Client != null) {
                s3Client.close();
            }
            if (s3AsyncClient != null) {
                s3AsyncClient.close();
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    void testBasicTransferManagerWorkflow() throws Exception {
        // Given: Upload a multipart object with 5MB parts
        int uploadPartSize = 5 * 1024 * 1024; // 5MB
        int totalFileSize = 15 * 1024 * 1024; // 15MB (3 parts)
        byte[] testData = createTestData(totalFileSize);
        
        System.out.println("=== Basic Transfer Manager Workflow Test ===");
        System.out.println("Upload part size: " + (uploadPartSize / 1024 / 1024) + "MB");
        System.out.println("Download part size: 8MB (configured in Transfer Manager)");
        System.out.println("Total file size: " + (totalFileSize / 1024 / 1024) + "MB");
        
        // Upload the object using standard multipart
        uploadMultipartObject(testData, uploadPartSize);
        
        // Test 3: Download using Transfer Manager with presigned URL
        testTransferManagerPresignedUrlDownload(testData);
        
        System.out.println("✅ All basic Transfer Manager tests passed!");
    }

    private void testTransferManagerDownloadToBytes(byte[] expectedData) throws Exception {
        System.out.println("\n--- Test 1: Transfer Manager download() to bytes ---");
        
        // Create regular download request
        DownloadRequest<ResponseBytes<GetObjectResponse>> downloadRequest = DownloadRequest.builder()
                .getObjectRequest(req -> req.bucket(TEST_BUCKET).key(testKey))
                .responseTransformer(AsyncResponseTransformer.toBytes())
                .addTransferListener(LoggingTransferListener.create())
                .build();

        // Download using Transfer Manager
        Download<ResponseBytes<GetObjectResponse>> download = transferManager.download(downloadRequest);

        // Wait for completion and verify
        CompletedDownload<ResponseBytes<GetObjectResponse>> completed = download.completionFuture().get(30, TimeUnit.SECONDS);
        ResponseBytes<GetObjectResponse> result = completed.result();
        
        assertThat(result.asByteArray()).hasSize(expectedData.length);
        assertThat(result.asByteArray()).isEqualTo(expectedData);
        
        System.out.println("✅ Transfer Manager download() to bytes works correctly");
        System.out.println("   Downloaded " + result.asByteArray().length + " bytes");
        System.out.println("   Progress: " + download.progress().snapshot());
    }

    private void testTransferManagerDownloadToFile(byte[] expectedData) throws Exception {
        System.out.println("\n--- Test 2: Transfer Manager downloadFile() ---");
        
        Path tempFile = Files.createTempFile("basic-transfer-manager-test", ".dat");
        
        try {
            // Create file download request
            DownloadFileRequest downloadRequest = DownloadFileRequest.builder()
                    .getObjectRequest(req -> req.bucket(TEST_BUCKET).key(testKey))
                    .destination(tempFile)
                    .addTransferListener(LoggingTransferListener.create())
                    .build();

            // Download to file using Transfer Manager
            FileDownload download = transferManager.downloadFile(downloadRequest);

            // Wait for completion and verify
            CompletedFileDownload completed = download.completionFuture().get(30, TimeUnit.SECONDS);
            
            // Verify file contents
            byte[] downloadedData = Files.readAllBytes(tempFile);
            assertThat(downloadedData).hasSize(expectedData.length);
            assertThat(downloadedData).isEqualTo(expectedData);
            
            System.out.println("✅ Transfer Manager downloadFile() works correctly");
            System.out.println("   Downloaded to file: " + tempFile);
            System.out.println("   File size: " + downloadedData.length + " bytes");
            System.out.println("   Progress: " + download.progress().snapshot());
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void uploadMultipartObject(byte[] data, int partSize) {
        System.out.println("\nUploading multipart object...");
        
        // Initiate multipart upload
        CreateMultipartUploadRequest createRequest = CreateMultipartUploadRequest.builder()
                .bucket(TEST_BUCKET)
                .key(testKey)
                .build();
        CreateMultipartUploadResponse createResponse = s3Client.createMultipartUpload(createRequest);
        String uploadId = createResponse.uploadId();
        
        List<CompletedPart> completedParts = new java.util.ArrayList<>();
        int partNumber = 1;
        int offset = 0;

        // Upload parts
        while (offset < data.length) {
            int currentPartSize = Math.min(partSize, data.length - offset);
            byte[] partData = Arrays.copyOfRange(data, offset, offset + currentPartSize);

            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(TEST_BUCKET)
                    .key(testKey)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();

            UploadPartResponse uploadPartResponse = s3Client.uploadPart(
                    uploadPartRequest, RequestBody.fromBytes(partData));

            completedParts.add(CompletedPart.builder()
                    .partNumber(partNumber)
                    .eTag(uploadPartResponse.eTag())
                    .build());

            System.out.println("   Uploaded part " + partNumber + " (" + currentPartSize + " bytes)");
            offset += currentPartSize;
            partNumber++;
        }

        // Complete multipart upload
        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket(TEST_BUCKET)
                .key(testKey)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build();
        s3Client.completeMultipartUpload(completeRequest);
        
        System.out.println("Multipart upload completed successfully");
    }

    private void testTransferManagerPresignedUrlDownload(byte[] expectedData) throws Exception {
        System.out.println("\n--- Test 3: Transfer Manager presigned URL download ---");
        
        // Create temp file path but delete it first so Transfer Manager can create it
        Path tempFile = Files.createTempFile("presigned-url-test", ".dat");
        Files.deleteIfExists(tempFile);
        
        // Create a separate Transfer Manager with multipart-enabled client for presigned URLs
        S3AsyncClient multipartEnabledClient = S3AsyncClient.builder()
                .region(TEST_REGION)
                .multipartEnabled(true)
                .build();
        
        S3TransferManager presignedTransferManager = S3TransferManager.builder()
                .s3Client(multipartEnabledClient)
                .build();
        
        try {
            // Create presigned URL download request  
            // First generate a presigned URL
            try (S3Presigner presigner = S3Presigner.builder().region(TEST_REGION).build()) {
                GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(10))
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(TEST_BUCKET)
                                .key(testKey)
                                .build())
                        .build();
                
                PresignedGetObjectRequest presignedGetObjectRequest = presigner.presignGetObject(presignRequest);
                
                PresignedDownloadFileRequest downloadRequest = PresignedDownloadFileRequest.builder()
                        .presignedUrl(presignedGetObjectRequest.url())
                        .destination(tempFile)
                        .addTransferListener(LoggingTransferListener.create())
                        .build();

                // Download using Transfer Manager with multipart-enabled client
                FileDownload download = presignedTransferManager.downloadFile(downloadRequest);

                // Wait for completion and verify
                CompletedFileDownload completed = download.completionFuture().get(30, TimeUnit.SECONDS);
                
                // Verify file contents
                byte[] downloadedData = Files.readAllBytes(tempFile);
                assertThat(downloadedData).hasSize(expectedData.length);
                assertThat(downloadedData).isEqualTo(expectedData);
                
                System.out.println("✅ Transfer Manager presigned URL download works correctly");
                System.out.println("   Downloaded to file: " + tempFile);
                System.out.println("   File size: " + downloadedData.length + " bytes");
                System.out.println("   Progress: " + download.progress().snapshot());
            }
        } finally {
            presignedTransferManager.close();
            multipartEnabledClient.close();
            Files.deleteIfExists(tempFile);
        }
    }

    private byte[] createTestData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 256);
        }
        return data;
    }
}
