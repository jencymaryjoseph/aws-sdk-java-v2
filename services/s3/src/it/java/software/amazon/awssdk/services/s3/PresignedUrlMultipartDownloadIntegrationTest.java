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

package software.amazon.awssdk.services.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;

public class PresignedUrlMultipartDownloadIntegrationTest extends S3IntegrationTestBase {

    private static final String BUCKET_NAME = "presigned-multipart-test-" + System.currentTimeMillis();
    private static S3AsyncClient multipartClient;

    @BeforeAll
    static void setUpBucket() throws Exception {
        System.setProperty("aws.crt.debugnative", "true");
        software.amazon.awssdk.crt.Log.initLoggingToStdout(software.amazon.awssdk.crt.Log.LogLevel.Warn);
        s3 = s3ClientBuilder().build();
        s3Async = s3AsyncClientBuilder().build();
        
        createBucket(BUCKET_NAME);

        multipartClient = S3AsyncClient.builder()
                                      .region(DEFAULT_REGION)
                                      .credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                                      .multipartEnabled(true)
                                      .build();
    }

    @AfterAll
    static void cleanUp() {
        deleteBucketAndAllContents(BUCKET_NAME);
        if (multipartClient != null) {
            multipartClient.close();
        }
        if (s3 != null) {
            s3.close();
        }
        if (s3Async != null) {
            s3Async.close();
        }
    }

    @Test
    void downloadLargeObject_withPresignedUrl_usesMultipart() {
        String key = "large-object-presigned-15mb";
        byte[] content = generateContent(15 * 1024 * 1024); // 15MB
        
        uploadObject(key, content);
        URL presignedUrl = generatePresignedUrl(key);

        List<String> rangeHeaders = new ArrayList<>();
        SdkAsyncHttpClient spyHttpClient = spy(NettyNioAsyncHttpClient.create());
        doAnswer(invocation -> {
            Object request = invocation.getArgument(0);
            software.amazon.awssdk.http.async.AsyncExecuteRequest asyncRequest = 
                (software.amazon.awssdk.http.async.AsyncExecuteRequest) request;
            software.amazon.awssdk.http.SdkHttpRequest httpRequest = asyncRequest.request();
            httpRequest.headers().forEach((name, values) -> {
                if (name.toLowerCase().contains("range")) {
                    rangeHeaders.addAll(values);
                }
            });
            return invocation.callRealMethod();
        }).when(spyHttpClient).execute(any());
        
        S3AsyncClient customClient = S3AsyncClient.builder()
                                                  .region(DEFAULT_REGION)
                                                  .credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                                                  .multipartEnabled(true)
                                                  .httpClient(spyHttpClient)
                                                  .build();

        byte[] downloaded = downloadWithPresignedUrlUsingClient(presignedUrl, customClient);
        customClient.close();

        assertThat(downloaded).isEqualTo(content);
        // Confirmed: presigned URLs DO use multipart when client is multipart-enabled
        assertThat(rangeHeaders).hasSizeGreaterThanOrEqualTo(2);
        assertThat(rangeHeaders.get(0)).startsWith("bytes=0-");
    }

    @Test
    void downloadObject_withNonMultipartClient_usesSinglePart() {
        String key = "single-part-test";
        byte[] content = generateContent(15 * 1024 * 1024); // 15MB
        
        uploadObject(key, content);
        URL presignedUrl = generatePresignedUrl(key);

        List<String> rangeHeaders = new ArrayList<>();
        SdkAsyncHttpClient spyHttpClient = spy(NettyNioAsyncHttpClient.create());
        doAnswer(invocation -> {
            Object request = invocation.getArgument(0);
            software.amazon.awssdk.http.async.AsyncExecuteRequest asyncRequest = 
                (software.amazon.awssdk.http.async.AsyncExecuteRequest) request;
            software.amazon.awssdk.http.SdkHttpRequest httpRequest = asyncRequest.request();
            httpRequest.headers().forEach((name, values) -> {
                if (name.toLowerCase().contains("range")) {
                    rangeHeaders.addAll(values);
                }
            });
            return invocation.callRealMethod();
        }).when(spyHttpClient).execute(any());
        
        // Client WITHOUT multipart enabled
        S3AsyncClient nonMultipartClient = S3AsyncClient.builder()
                                                        .region(DEFAULT_REGION)
                                                        .credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                                                        .httpClient(spyHttpClient)
                                                        .build();

        byte[] downloaded = downloadWithPresignedUrlUsingClient(presignedUrl, nonMultipartClient);
        nonMultipartClient.close();

        assertThat(downloaded).isEqualTo(content);
        // Non-multipart client should not use range headers
        assertThat(rangeHeaders).isEmpty();
    }

    @Test
    void downloadObject_withRangeHeader_shouldRespectRange() {
        String key = "range-test";
        byte[] content = generateContent(1024 * 1024); // 1MB
        
        uploadObject(key, content);
        URL presignedUrl = generatePresignedUrl(key);

        byte[] partialDownload = multipartClient.presignedUrlExtension()
                                               .getObject(PresignedUrlDownloadRequest.builder()
                                                                                    .presignedUrl(presignedUrl)
                                                                                    .range("bytes=0-524287")
                                                                                    .build(),
                                                         AsyncResponseTransformer.toBytes())
                                               .join()
                                               .asByteArray();

        assertThat(partialDownload).hasSize(524288);
        assertThat(partialDownload).isEqualTo(java.util.Arrays.copyOfRange(content, 0, 524288));
    }

    @Test
    void downloadObject_withExpiredUrl_shouldFail() {
        String key = "expired-test";
        byte[] content = generateContent(1024);
        
        uploadObject(key, content);
        
        URL expiredUrl;
        try (S3Presigner presigner = S3Presigner.builder()
                                                .region(DEFAULT_REGION)
                                                .credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                                                .build()) {
            expiredUrl = presigner.presignGetObject(GetObjectPresignRequest.builder()
                                                                          .signatureDuration(Duration.ofSeconds(1))
                                                                          .getObjectRequest(GetObjectRequest.builder()
                                                                                                          .bucket(BUCKET_NAME)
                                                                                                          .key(key)
                                                                                                          .build())
                                                                          .build())
                                 .url();
        }

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThatThrownBy(() -> downloadWithPresignedUrlUsingClient(expiredUrl, multipartClient))
                .hasRootCauseInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
    }

    @Test
    void downloadObject_withNonExistentKey_shouldFail() {
        String nonExistentKey = "does-not-exist";
        URL presignedUrl = generatePresignedUrl(nonExistentKey);

        assertThatThrownBy(() -> downloadWithPresignedUrl(presignedUrl))
                .hasRootCauseInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
    }

    private byte[] generateContent(int size) {
        byte[] content = new byte[size];
        new Random().nextBytes(content);
        return content;
    }

    private void uploadObject(String key, byte[] content) {
        s3.putObject(PutObjectRequest.builder()
                                    .bucket(BUCKET_NAME)
                                    .key(key)
                                    .build(),
                    RequestBody.fromBytes(content));
    }

    private URL generatePresignedUrl(String key) {
        try (S3Presigner presigner = S3Presigner.builder()
                                                .region(DEFAULT_REGION)
                                                .credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                                                .build()) {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                                                                    .signatureDuration(Duration.ofMinutes(10))
                                                                    .getObjectRequest(GetObjectRequest.builder()
                                                                                                    .bucket(BUCKET_NAME)
                                                                                                    .key(key)
                                                                                                    .build())
                                                                    .build())
                           .url();
        }
    }

    private byte[] downloadWithPresignedUrl(URL presignedUrl) {
        return downloadWithPresignedUrlUsingClient(presignedUrl, multipartClient);
    }

    private byte[] downloadWithPresignedUrlUsingClient(URL presignedUrl, S3AsyncClient client) {
        try {
            return client.presignedUrlExtension()
                         .getObject(PresignedUrlDownloadRequest.builder()
                                                              .presignedUrl(presignedUrl)
                                                              .build(),
                                   AsyncResponseTransformer.toBytes())
                         .join()
                         .asByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download with presigned URL", e);
        }
    }
}