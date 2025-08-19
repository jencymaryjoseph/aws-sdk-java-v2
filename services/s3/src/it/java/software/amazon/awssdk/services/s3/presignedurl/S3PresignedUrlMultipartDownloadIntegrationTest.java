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

package software.amazon.awssdk.services.s3.presignedurl;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static software.amazon.awssdk.testutils.service.S3BucketUtils.temporaryBucketName;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3IntegrationTestBase;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.testutils.RandomTempFile;

@Timeout(value = 60, unit = SECONDS)
public class S3PresignedUrlMultipartDownloadIntegrationTest extends S3IntegrationTestBase {

    private static final String TEST_BUCKET = temporaryBucketName(S3PresignedUrlMultipartDownloadIntegrationTest.class);
    private static final String TEST_KEY = "testfile.dat";
    private static final int OBJ_SIZE = 1024 * 1024 * 15; // 15MB
    private static RandomTempFile testFile;
    private static S3AsyncClient s3AsyncClient;
    private static S3Presigner s3Presigner;
    private static final RequestCapturingInterceptor interceptor = new RequestCapturingInterceptor();

    private static class RequestCapturingInterceptor implements ExecutionInterceptor {
        private final List<String> rangeHeaders = new CopyOnWriteArrayList<>();

        @Override
        public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
            SdkHttpRequest request = context.httpRequest();
            request.firstMatchingHeader("Range").ifPresent(rangeHeaders::add);
            return request;
        }

        public List<String> getRangeHeaders() {
            return rangeHeaders;
        }

        public void reset() {
            rangeHeaders.clear();
        }
    }

    @BeforeAll
    public static void setup() throws Exception {
        setUp();
        createBucket(TEST_BUCKET);
        testFile = new RandomTempFile(OBJ_SIZE);
        s3AsyncClient = S3AsyncClient.builder()
                                     .multipartEnabled(true)
                                     .overrideConfiguration(o -> o.addExecutionInterceptor(interceptor))
                                     .build();
        s3Presigner = S3Presigner.builder()
                                 .region(DEFAULT_REGION)
                                 .credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                                 .build();
        s3.putObject(r -> r.bucket(TEST_BUCKET).key(TEST_KEY), RequestBody.fromFile(testFile.toPath()));
    }

    @AfterAll
    public static void teardown() throws Exception {
        s3AsyncClient.close();
        s3Presigner.close();
        testFile.delete();
        deleteBucketAndAllContents(TEST_BUCKET);
    }

    @BeforeEach
    void resetInterceptor() {
        interceptor.reset();
    }

    @Test
    void presignedUrlDownload_multipartFile_shouldDownloadCorrectly() throws Exception {
        URL presignedUrl = createPresignedUrl();
        
        ResponseBytes<GetObjectResponse> result = s3AsyncClient.presignedUrlExtension()
                                                               .getObject(PresignedUrlDownloadRequest.builder()
                                                                                                     .presignedUrl(presignedUrl)
                                                                                                     .build(),
                                                                         AsyncResponseTransformer.toBytes())
                                                               .join();

        assertThat(result.asByteArray()).hasSize(OBJ_SIZE);
        assertThat(result.asByteArray()).isEqualTo(Files.readAllBytes(testFile.toPath()));
        
        // Verify multipart download occurred (multiple range requests)
        assertThat(interceptor.getRangeHeaders()).hasSizeGreaterThan(1);
    }

    @Test
    void presignedUrlDownload_toFile_shouldDownloadCorrectly() throws Exception {
        URL presignedUrl = createPresignedUrl();
        Path downloadFile = Files.createTempFile("download-", ".dat");
        Files.delete(downloadFile);
        
        try {
            GetObjectResponse response = s3AsyncClient.presignedUrlExtension()
                                                      .getObject(PresignedUrlDownloadRequest.builder()
                                                                                           .presignedUrl(presignedUrl)
                                                                                           .build(),
                                                                AsyncResponseTransformer.toFile(downloadFile))
                                                      .join();

            assertThat(Files.size(downloadFile)).isEqualTo(OBJ_SIZE);
            assertThat(Files.readAllBytes(downloadFile)).isEqualTo(Files.readAllBytes(testFile.toPath()));
        } finally {
            Files.deleteIfExists(downloadFile);
        }
    }

    @Test
    void presignedUrlDownload_withRange_shouldDownloadPartialContent() throws Exception {
        URL presignedUrl = createPresignedUrl();
        
        ResponseBytes<GetObjectResponse> result = s3AsyncClient.presignedUrlExtension()
                                                               .getObject(PresignedUrlDownloadRequest.builder()
                                                                                                     .presignedUrl(presignedUrl)
                                                                                                     .range("bytes=0-1023")
                                                                                                     .build(),
                                                                         AsyncResponseTransformer.toBytes())
                                                               .join();

        assertThat(result.asByteArray()).hasSize(1024);
        byte[] expectedBytes = new byte[1024];
        System.arraycopy(Files.readAllBytes(testFile.toPath()), 0, expectedBytes, 0, 1024);
        assertThat(result.asByteArray()).isEqualTo(expectedBytes);
    }

    private URL createPresignedUrl() {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                                                                        .signatureDuration(Duration.ofMinutes(10))
                                                                        .getObjectRequest(GetObjectRequest.builder()
                                                                                                          .bucket(TEST_BUCKET)
                                                                                                          .key(TEST_KEY)
                                                                                                          .build())
                                                                        .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url();
    }
}