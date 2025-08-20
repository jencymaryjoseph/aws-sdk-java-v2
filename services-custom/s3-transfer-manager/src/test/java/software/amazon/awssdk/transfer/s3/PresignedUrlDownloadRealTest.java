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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.transfer.s3.model.CompletedDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload;
import software.amazon.awssdk.transfer.s3.model.Download;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.FileDownload;
import software.amazon.awssdk.transfer.s3.model.PresignedDownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.PresignedDownloadRequest;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;

/**
 * Real integration test for presigned URL download functionality.
 * Tests actual S3 operations with presigned URLs.
 */
public class PresignedUrlDownloadRealTest {

    private static final Region TEST_REGION = Region.US_EAST_2;
    private static final String TEST_BUCKET = "jency-test-bucket"; // Update with your bucket

    private S3Client s3Client;
    private S3AsyncClient s3AsyncClient;
    private S3TransferManager transferManager;
    private String testKey;

    @BeforeEach
    void setUp() {
        s3Client = S3Client.builder().region(TEST_REGION).build();
        s3AsyncClient = S3AsyncClient.builder().region(TEST_REGION).build();
        transferManager = S3TransferManager.builder()
                                           .s3Client(s3AsyncClient)
                                           .build();
        testKey = "presigned-test-" + System.currentTimeMillis();
    }

    @AfterEach
    void tearDown() {
        try {
            // Cleanup S3 object
            s3Client.deleteObject(builder -> builder.bucket(TEST_BUCKET).key(testKey));
        } catch (Exception e) {
            // Ignore cleanup errors
        }
        
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
    void testNormalMultipartDownload() throws Exception {
        System.out.println("=== Normal Transfer Manager Multipart Download Test ===");
        
        // Given: Upload large test data using multipart upload to enable multipart download
        byte[] testData = createTestData(20 * 1024 * 1024); // 20MB
        
        // Upload using Transfer Manager to create multipart object
        S3TransferManager uploadTransferManager = S3TransferManager.builder()
            .s3Client(S3AsyncClient.builder().region(TEST_REGION).multipartEnabled(true).build())
            .build();
        
        Path tempUploadFile = Files.createTempFile("upload-test", ".dat");
        Files.write(tempUploadFile, testData);
        
        uploadTransferManager.uploadFile(builder -> builder
            .putObjectRequest(req -> req.bucket(TEST_BUCKET).key(testKey))
            .source(tempUploadFile)
        ).completionFuture().get(60, TimeUnit.SECONDS);
        
        Files.deleteIfExists(tempUploadFile);
        uploadTransferManager.close();
        
        // Create multipart-enabled S3 client
        S3AsyncClient multipartS3Client = S3AsyncClient.builder()
            .region(TEST_REGION)
            .multipartEnabled(true)
            .build();
        
        S3TransferManager multipartTransferManager = S3TransferManager.builder()
            .s3Client(multipartS3Client)
            .build();
        
        Path downloadPath = Files.createTempFile("normal-multipart-test", ".dat");
        
        try {
            // When: Download using normal Transfer Manager multipart
            DownloadFileRequest downloadRequest = DownloadFileRequest.builder()
                .getObjectRequest(GetObjectRequest.builder()
                    .bucket(TEST_BUCKET)
                    .key(testKey)
                    .build())
                .destination(downloadPath)
                .addTransferListener(LoggingTransferListener.create())
                .build();
            
            FileDownload download = multipartTransferManager.downloadFile(downloadRequest);
            CompletedFileDownload completed = download.completionFuture().get(60, TimeUnit.SECONDS);
            
            // Then: Verify download completed successfully
            assertThat(Files.exists(downloadPath)).isTrue();
            assertThat(Files.size(downloadPath)).isEqualTo(testData.length);
            assertThat(Files.readAllBytes(downloadPath)).isEqualTo(testData);
            
            System.out.println("✅ Normal multipart download completed successfully");
            System.out.println("   Downloaded " + Files.size(downloadPath) + " bytes using normal multipart");
            System.out.println("   Final Progress: " + download.progress().snapshot());
            
        } finally {
            Files.deleteIfExists(downloadPath);
            multipartTransferManager.close();
            multipartS3Client.close();
        }
    }

    @Test
    void testPresignedUrlMultipartDownload() throws Exception {
        System.out.println("=== Presigned URL Multipart Download Test ===");
        
        // Given: Upload large test data to trigger multipart (>16MB threshold)
        byte[] testData = createTestData(20 * 1024 * 1024); // 20MB
        s3Client.putObject(builder -> builder.bucket(TEST_BUCKET).key(testKey), 
                          RequestBody.fromBytes(testData));
        
        // Create multipart-enabled S3 client
        S3AsyncClient multipartS3Client = S3AsyncClient.builder()
            .region(TEST_REGION)
            .multipartEnabled(true)
            .build();
        
        S3TransferManager multipartTransferManager = S3TransferManager.builder()
            .s3Client(multipartS3Client)
            .build();
        
        // Generate presigned URL
        S3Presigner presigner = S3Presigner.builder().region(TEST_REGION).build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(GetObjectRequest.builder()
                    .bucket(TEST_BUCKET)
                    .key(testKey)
                    .build())
                .build());
        
        Path downloadPath = Files.createTempFile("presigned-multipart-test", ".dat");
        
        try {
            // When: Download using presigned URL with multipart enabled
            PresignedDownloadFileRequest downloadRequest = PresignedDownloadFileRequest.builder()
                .presignedUrlDownloadRequest(PresignedUrlDownloadRequest.builder()
                    .presignedUrl(presignedRequest.url())
                    .build())
                .destination(downloadPath)
                .addTransferListener(LoggingTransferListener.create())
                .build();
            
            FileDownload download = multipartTransferManager.downloadFileWithPresignedUrl(downloadRequest);
            CompletedFileDownload completed = download.completionFuture().get(60, TimeUnit.SECONDS);
            

            assertThat(Files.exists(downloadPath)).isTrue();
            assertThat(Files.size(downloadPath)).isEqualTo(testData.length);
            assertThat(Files.readAllBytes(downloadPath)).isEqualTo(testData);
            
            System.out.println("✅ Presigned URL multipart download completed successfully");
            System.out.println("   Downloaded " + Files.size(downloadPath) + " bytes using presigned URL multipart");
            System.out.println("   Final Progress: " + download.progress().snapshot());
            
        } finally {
            Files.deleteIfExists(downloadPath);
            multipartTransferManager.close();
            multipartS3Client.close();
            presigner.close();
        }
    }

    @Test
    void testPresignedUrlNormalDownload() throws Exception {
        System.out.println("=== Presigned URL Normal (Non-Multipart) Download Test ===");
        
        // Given: Upload test data (same large file as multipart test for comparison)
        byte[] testData = createTestData(20 * 1024 * 1024); // 20MB
        s3Client.putObject(builder -> builder.bucket(TEST_BUCKET).key(testKey), 
                          RequestBody.fromBytes(testData));
        
        // Create normal (non-multipart) S3 client
        S3AsyncClient normalS3Client = S3AsyncClient.builder()
            .region(TEST_REGION)
            .multipartEnabled(false)
            .build();
        
        S3TransferManager normalTransferManager = S3TransferManager.builder()
            .s3Client(normalS3Client)
            .build();
        
        // Generate presigned URL
        S3Presigner presigner = S3Presigner.builder().region(TEST_REGION).build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(GetObjectRequest.builder()
                    .bucket(TEST_BUCKET)
                    .key(testKey)
                    .build())
                .build());
        
        Path downloadPath = Files.createTempFile("presigned-normal-test", ".dat");
        
        try {
            // When: Download using presigned URL with normal (non-multipart) client
            PresignedDownloadFileRequest downloadRequest = PresignedDownloadFileRequest.builder()
                .presignedUrlDownloadRequest(PresignedUrlDownloadRequest.builder()
                    .presignedUrl(presignedRequest.url())
                    .build())
                .destination(downloadPath)
                .addTransferListener(LoggingTransferListener.create())
                .build();
            
            FileDownload download = normalTransferManager.downloadFileWithPresignedUrl(downloadRequest);
            CompletedFileDownload completed = download.completionFuture().get(60, TimeUnit.SECONDS);
            
            // Then: Verify normal download completed
            assertThat(Files.exists(downloadPath)).isTrue();
            assertThat(Files.size(downloadPath)).isEqualTo(testData.length);
            assertThat(Files.readAllBytes(downloadPath)).isEqualTo(testData);
            
            System.out.println("✅ Presigned URL normal download completed successfully");
            System.out.println("   Downloaded " + Files.size(downloadPath) + " bytes using presigned URL normal client");
            System.out.println("   Final Progress: " + download.progress().snapshot());
            
        } finally {
            Files.deleteIfExists(downloadPath);
            normalTransferManager.close();
            normalS3Client.close();
            presigner.close();
        }
    }

    @Test
    void testNormalDownloadWithoutPresignedUrl() throws Exception {
        System.out.println("=== Normal (Non-Multipart) Download Without Presigned URL Test ===");
        
        // Given: Upload test data (same large file for comparison)
        byte[] testData = createTestData(20 * 1024 * 1024); // 20MB
        s3Client.putObject(builder -> builder.bucket(TEST_BUCKET).key(testKey), 
                          RequestBody.fromBytes(testData));
        
        // Create normal (non-multipart) S3 client
        S3AsyncClient normalS3Client = S3AsyncClient.builder()
            .region(TEST_REGION)
            .multipartEnabled(false)
            .build();
        
        S3TransferManager normalTransferManager = S3TransferManager.builder()
            .s3Client(normalS3Client)
            .build();
        
        Path downloadPath = Files.createTempFile("normal-download-test", ".dat");
        
        try {
            // When: Download using normal Transfer Manager (no presigned URL)
            DownloadFileRequest downloadRequest = DownloadFileRequest.builder()
                .getObjectRequest(GetObjectRequest.builder()
                    .bucket(TEST_BUCKET)
                    .key(testKey)
                    .build())
                .destination(downloadPath)
                .addTransferListener(LoggingTransferListener.create())
                .build();
            
            FileDownload download = normalTransferManager.downloadFile(downloadRequest);
            CompletedFileDownload completed = download.completionFuture().get(60, TimeUnit.SECONDS);
            
            // Then: Verify normal download completed
            assertThat(Files.exists(downloadPath)).isTrue();
            assertThat(Files.size(downloadPath)).isEqualTo(testData.length);
            assertThat(Files.readAllBytes(downloadPath)).isEqualTo(testData);
            
            System.out.println("✅ Normal download (without presigned URL) completed successfully");
            System.out.println("   Downloaded " + Files.size(downloadPath) + " bytes using normal client");
            System.out.println("   Final Progress: " + download.progress().snapshot());
            
        } finally {
            Files.deleteIfExists(downloadPath);
            normalTransferManager.close();
            normalS3Client.close();
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