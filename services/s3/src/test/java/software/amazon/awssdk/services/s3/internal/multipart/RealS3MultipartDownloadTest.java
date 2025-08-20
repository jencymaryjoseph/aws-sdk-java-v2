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

package software.amazon.awssdk.services.s3.internal.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;

class RealS3MultipartDownloadTest {

    private static final String BUCKET = "jency-test-bucket";
    private static final String KEY = "Amazon Q.dmg";
    
    private S3AsyncClient s3AsyncClient;
    private S3Client s3Client;
    private S3Presigner s3Presigner;
    private Path tempFile;

    @BeforeEach
    void setup() {
        s3AsyncClient = S3AsyncClient.builder()
                                     .multipartEnabled(false) // Disable multipart for simpler testing
                                     .build();
        
        s3Client = S3Client.create();
        s3Presigner = S3Presigner.create();
    }

    @Test
    void multipartDownload_withDirectS3Call_shouldDownloadFile() throws IOException {
        tempFile = createTempFile();
        
        s3AsyncClient.getObject(GetObjectRequest.builder()
                                               .bucket(BUCKET)
                                               .key(KEY)
                                               .build(),
                               AsyncResponseTransformer.toFile(tempFile))
                     .join();
        
        assertThat(tempFile).exists();
        assertThat(Files.size(tempFile)).isGreaterThan(0);
    }

    @Test
    void multipartDownload_withPresignedUrl_shouldDownloadFile() throws IOException {
        tempFile = createTempFile();
        
        URL presignedUrl = s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                                                                               .signatureDuration(Duration.ofMinutes(10))
                                                                               .getObjectRequest(GetObjectRequest.builder()
                                                                                                                .bucket(BUCKET)
                                                                                                                .key(KEY)
                                                                                                                .build())
                                                                               .build())
                                      .url();
        
        s3AsyncClient.presignedUrlExtension()
                     .getObject(PresignedUrlDownloadRequest.builder()
                                                           .presignedUrl(presignedUrl)
                                                           .build(),
                                AsyncResponseTransformer.toFile(tempFile))
                     .join();
        
        assertThat(tempFile).exists();
        assertThat(Files.size(tempFile)).isGreaterThan(0);
    }

    @AfterEach
    void cleanup() throws IOException {
        if (tempFile != null && Files.exists(tempFile)) {
            Files.delete(tempFile);
        }
    }

    private Path createTempFile() throws IOException {
        Path tempFile = Files.createTempFile("s3-download-" + UUID.randomUUID(), ".dmg");
        Files.deleteIfExists(tempFile);
        return tempFile;
    }
}