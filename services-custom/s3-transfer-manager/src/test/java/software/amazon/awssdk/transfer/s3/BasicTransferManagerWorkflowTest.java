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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.transfer.s3.model.CompletedDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload;
import software.amazon.awssdk.transfer.s3.model.Download;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.DownloadRequest;
import software.amazon.awssdk.transfer.s3.model.FileDownload;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.PresignedDownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.ResumableFileDownload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;

public class BasicTransferManagerWorkflowTest {

    private static final Region TEST_REGION = Region.US_EAST_2;
    private static final String TEST_BUCKET = "jency-test-bucket";
    private static final int FILE_SIZE = 15 * 1024 * 1024; // 15MB

    private S3Client s3Client;
    private S3AsyncClient s3AsyncClient;
    private S3TransferManager transferManager;
    private String testKey;
    private byte[] testData;

    @BeforeEach
    void setUp() {
        s3Client = S3Client.builder().region(TEST_REGION).build();
        s3AsyncClient = S3AsyncClient.builder()
                                     .region(TEST_REGION)
                                     .multipartEnabled(true)
                                     .build();

        transferManager = S3TransferManager.builder()
                                           .s3Client(s3AsyncClient)
                                           .build();

        testKey = "test-object-" + System.currentTimeMillis();
        testData = createTestData(FILE_SIZE);
    }

    @AfterEach
    void tearDown() {
        try {
            // Cleanup test object
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
    void testUploadFile() throws Exception {
        uploadTestFile();
    }

    @Test
    void testDownloadToBytes() throws Exception {
        uploadTestFile();
        testTransferManagerDownloadToBytes();
    }

    @Test
    void testDownloadToFile() throws Exception {
        uploadTestFile();
        testTransferManagerDownloadToFile();
    }

    @Test
    void testPauseResumeDownload() throws Exception {
        uploadTestFile();
        testTransferManagerPauseResume();
    }

    @Test
    void testPresignedDownloadToFile() throws Exception {
        uploadTestFile();
        testPresignedUrlDownloadToFile();
    }

    @Test
    void testPresignedPauseResume() throws Exception {
        uploadTestFile();
        testPresignedUrlPauseResume();
    }

    private void uploadTestFile() throws Exception {
        System.out.println("--- Uploading test file ---");

        Path tempFile = Files.createTempFile("upload-test", ".dat");
        Files.write(tempFile, testData);

        try {
            UploadFileRequest uploadRequest = UploadFileRequest.builder()
                                                              .putObjectRequest(req -> req.bucket(TEST_BUCKET).key(testKey))
                                                              .source(tempFile)
                                                              .addTransferListener(LoggingTransferListener.create())
                                                              .build();

            FileUpload upload = transferManager.uploadFile(uploadRequest);
            CompletedFileUpload completed = upload.completionFuture().get(30, TimeUnit.SECONDS);

            assertThat(completed.response()).isNotNull();
            System.out.println("✅ Upload completed: " + testData.length + " bytes");

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void testTransferManagerDownloadToBytes() throws Exception {
        System.out.println("--- Test: Download to bytes ---");

        DownloadRequest<ResponseBytes<GetObjectResponse>> downloadRequest = DownloadRequest.builder()
                                                                                           .getObjectRequest(req -> req.bucket(TEST_BUCKET).key(testKey))
                                                                                           .responseTransformer(AsyncResponseTransformer.toBytes())
                                                                                           .addTransferListener(LoggingTransferListener.create())
                                                                                           .build();

        Download<ResponseBytes<GetObjectResponse>> download = transferManager.download(downloadRequest);
        CompletedDownload<ResponseBytes<GetObjectResponse>> completed = download.completionFuture().get(30, TimeUnit.SECONDS);

        byte[] downloadedData = completed.result().asByteArray();
        assertThat(downloadedData).hasSize(testData.length);
        assertThat(downloadedData).isEqualTo(testData);

        System.out.println("✅ Download to bytes completed: " + downloadedData.length + " bytes");
    }

    private void testTransferManagerDownloadToFile() throws Exception {
        System.out.println("--- Test: Download to file ---");

        Path tempFile = Files.createTempFile("download-test", ".dat");
        Files.deleteIfExists(tempFile);

        try {
            DownloadFileRequest downloadRequest = DownloadFileRequest.builder()
                                                                     .getObjectRequest(req -> req.bucket(TEST_BUCKET).key(testKey))
                                                                     .destination(tempFile)
                                                                     .addTransferListener(LoggingTransferListener.create())
                                                                     .build();

            FileDownload download = transferManager.downloadFile(downloadRequest);
            CompletedFileDownload completed = download.completionFuture().get(30, TimeUnit.SECONDS);

            byte[] downloadedData = Files.readAllBytes(tempFile);
            assertThat(downloadedData).hasSize(testData.length);
            assertThat(downloadedData).isEqualTo(testData);

            System.out.println("✅ Download to file completed: " + downloadedData.length + " bytes");

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void testTransferManagerPauseResume() throws Exception {
        System.out.println("--- Test: Pause/Resume download ---");

        Path tempFile = Files.createTempFile("pause-resume-test", ".dat");
        Files.deleteIfExists(tempFile);

        try {
            DownloadFileRequest downloadRequest = DownloadFileRequest.builder()
                                                                     .getObjectRequest(req -> req.bucket(TEST_BUCKET).key(testKey))
                                                                     .destination(tempFile)
                                                                     .addTransferListener(LoggingTransferListener.create())
                                                                     .build();

            FileDownload download = transferManager.downloadFile(downloadRequest);
            
            // Pause after a short delay
            Thread.sleep(100);
            ResumableFileDownload resumableDownload = download.pause();
            System.out.println("Download paused");

            // Resume the download
            FileDownload resumedDownload = transferManager.resumeDownloadFile(resumableDownload);
            CompletedFileDownload completed = resumedDownload.completionFuture().get(30, TimeUnit.SECONDS);

            byte[] downloadedData = Files.readAllBytes(tempFile);
            assertThat(downloadedData).hasSize(testData.length);
            assertThat(downloadedData).isEqualTo(testData);

            System.out.println("✅ Pause/Resume download completed: " + downloadedData.length + " bytes");

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void testPresignedUrlDownloadToFile() throws Exception {
        System.out.println("--- Test: Presigned URL download to file ---");

        S3Presigner presigner = S3Presigner.builder().region(TEST_REGION).build();
        
        try {
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                                                                            .signatureDuration(Duration.ofMinutes(10))
                                                                            .getObjectRequest(GetObjectRequest.builder()
                                                                                                              .bucket(TEST_BUCKET)
                                                                                                              .key(testKey)
                                                                                                              .build())
                                                                            .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            URL presignedUrl = presignedRequest.url();

            Path tempFile = Files.createTempFile("presigned-download-test", ".dat");
            Files.deleteIfExists(tempFile);

            try {
                PresignedUrlDownloadRequest presignedUrlDownloadRequest = 
                    PresignedUrlDownloadRequest.builder()
                        .presignedUrl(presignedUrl)
                        .build();

                PresignedDownloadFileRequest downloadRequest = PresignedDownloadFileRequest.builder()
                                                                                           .presignedUrlDownloadRequest(presignedUrlDownloadRequest)
                                                                                           .destination(tempFile)
                                                                                           .addTransferListener(LoggingTransferListener.create())
                                                                                           .build();

                FileDownload download = transferManager.downloadFile(downloadRequest);
                CompletedFileDownload completed = download.completionFuture().get(30, TimeUnit.SECONDS);

                byte[] downloadedData = Files.readAllBytes(tempFile);
                assertThat(downloadedData).hasSize(testData.length);
                assertThat(downloadedData).isEqualTo(testData);

                System.out.println("✅ Presigned URL download completed: " + downloadedData.length + " bytes");

            } finally {
                Files.deleteIfExists(tempFile);
            }

        } finally {
            presigner.close();
        }
    }

    private void testPresignedUrlPauseResume() throws Exception {
        System.out.println("--- Test: Presigned URL pause/resume download ---");

        S3Presigner presigner = S3Presigner.builder().region(TEST_REGION).build();
        
        try {
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                                                                            .signatureDuration(Duration.ofMinutes(10))
                                                                            .getObjectRequest(GetObjectRequest.builder()
                                                                                                              .bucket(TEST_BUCKET)
                                                                                                              .key(testKey)
                                                                                                              .build())
                                                                            .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            URL presignedUrl = presignedRequest.url();

            Path tempFile = Files.createTempFile("presigned-pause-resume-test", ".dat");
            Files.deleteIfExists(tempFile);

            try {
                PresignedUrlDownloadRequest presignedUrlDownloadRequest = 
                    PresignedUrlDownloadRequest.builder()
                        .presignedUrl(presignedUrl)
                        .build();

                PresignedDownloadFileRequest downloadRequest = PresignedDownloadFileRequest.builder()
                                                                                           .presignedUrlDownloadRequest(presignedUrlDownloadRequest)
                                                                                           .destination(tempFile)
                                                                                           .addTransferListener(LoggingTransferListener.create())
                                                                                           .build();

                FileDownload download = transferManager.downloadFile(downloadRequest);
                
                // Pause after a short delay
                Thread.sleep(100);
                ResumableFileDownload resumableDownload = download.pause();
                System.out.println("Presigned URL download paused");

                // Resume the download
                FileDownload resumedDownload = transferManager.resumeDownloadFile(resumableDownload);
                CompletedFileDownload completed = resumedDownload.completionFuture().get(30, TimeUnit.SECONDS);

                byte[] downloadedData = Files.readAllBytes(tempFile);
                assertThat(downloadedData).hasSize(testData.length);
                assertThat(downloadedData).isEqualTo(testData);

                System.out.println("✅ Presigned URL pause/resume completed: " + downloadedData.length + " bytes");

            } finally {
                Files.deleteIfExists(tempFile);
            }

        } finally {
            presigner.close();
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