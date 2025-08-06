// /*
//  * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License").
//  * You may not use this file except in compliance with the License.
//  * A copy of the License is located at
//  *
//  *  http://aws.amazon.com/apache2.0
//  *
//  * or in the "license" file accompanying this file. This file is distributed
//  * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
//  * express or implied. See the License for the specific language governing
//  * permissions and limitations under the License.
//  */
//
// package software.amazon.awssdk.services.s3.internal.multipart;
//
// import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
// import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
// import static com.github.tomakehurst.wiremock.client.WireMock.get;
// import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
// import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
// import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
// import static com.github.tomakehurst.wiremock.client.WireMock.verify;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
// import static org.junit.jupiter.api.Assertions.assertArrayEquals;
// import static org.junit.jupiter.params.provider.Arguments.arguments;
// import static software.amazon.awssdk.services.s3.internal.multipart.MultipartDownloadTestUtil.transformersSuppliers;
//
// import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
// import com.github.tomakehurst.wiremock.junit5.WireMockTest;
// import java.net.MalformedURLException;
// import java.net.URI;
// import java.util.Arrays;
// import java.util.List;
// import java.util.stream.Stream;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.params.ParameterizedTest;
// import org.junit.jupiter.params.provider.Arguments;
// import org.junit.jupiter.params.provider.MethodSource;
// import org.reactivestreams.Subscriber;
// import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
// import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
// import software.amazon.awssdk.core.SplittingTransformerConfiguration;
// import software.amazon.awssdk.core.async.AsyncResponseTransformer;
// import software.amazon.awssdk.regions.Region;
// import software.amazon.awssdk.services.s3.S3AsyncClient;
// import software.amazon.awssdk.services.s3.S3Configuration;
// import software.amazon.awssdk.services.s3.model.GetObjectResponse;
// import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
// import software.amazon.awssdk.services.s3.utils.AsyncResponseTransformerTestSupplier;
// import software.amazon.awssdk.utils.Pair;
//
// @WireMockTest
// class PresignedUrlMultipartDownloaderSubscriberWiremockTest {
//
//     private final String testBucket = "test-bucket";
//     private final String testKey = "test-key";
//     private final String testETag = "\"test-etag-12345\"";
//
//     private S3AsyncClient s3AsyncClient;
//     private PresignedUrlMultipartDownloadTestUtil util;
//     private WireMockRuntimeInfo wiremock;
//
//     @BeforeEach
//     public void init(WireMockRuntimeInfo wiremockInfo) {
//         this.wiremock = wiremockInfo;
//         s3AsyncClient = S3AsyncClient.builder()
//                                      .credentialsProvider(StaticCredentialsProvider.create(
//                                          AwsBasicCredentials.create("key", "secret")))
//                                      .region(Region.US_WEST_2)
//                                      .endpointOverride(URI.create("http://localhost:" + wiremock.getHttpPort()))
//                                      .serviceConfiguration(S3Configuration.builder()
//                                                                           .pathStyleAccessEnabled(true)
//                                                                           .build())
//                                      .build();
//         util = new PresignedUrlMultipartDownloadTestUtil(testBucket, testKey, testETag);
//     }
//
//     @ParameterizedTest
//     @MethodSource("argumentsProvider")
//     <T> void happyPath_shouldReceiveAllBodyPartInCorrectOrder(AsyncResponseTransformerTestSupplier<T> supplier,
//                                                               long objectSize,
//                                                               long configuredPartSize) throws MalformedURLException {
//         int expectedParts = (int) Math.ceil((double) objectSize / configuredPartSize);
//         byte[] expectedBody = util.stubAllRangeParts(testBucket, testKey, objectSize, configuredPartSize, testETag);
//
//         AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
//         AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
//             SplittingTransformerConfiguration.builder()
//                                              .bufferSizeInBytes(1024 * 32L)
//                                              .build());
//
//         // Create presigned URL that points to our WireMock server
//         PresignedUrlDownloadRequest presignedRequest = PresignedUrlDownloadRequest.builder()
//             .presignedUrl(URI.create(String.format("http://localhost:%d/%s/%s",
//                 wiremock.getHttpPort(), testBucket, testKey)).toURL())
//             .build();
//
//         Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber =
//             new PresignedUrlMultipartDownloaderSubscriber(
//                 s3AsyncClient,
//                 presignedRequest,
//                 configuredPartSize);
//
//         split.publisher().subscribe(subscriber);
//         T response = split.resultFuture().join();
//
//         byte[] body = supplier.body(response);
//         assertArrayEquals(expectedBody, body);
//         util.verifyCorrectAmountOfRangeRequestsMade(expectedParts);
//     }
//
//     @ParameterizedTest
//     @MethodSource("argumentsProvider")
//     <T> void errorOnFirstRequest_shouldCompleteExceptionally(AsyncResponseTransformerTestSupplier<T> supplier,
//                                                              long objectSize,
//                                                              long configuredPartSize) throws MalformedURLException {
//         // Stub first range request to fail
//         String firstRangeHeader = String.format("bytes=0-%d", configuredPartSize - 1);
//         stubFor(get(urlEqualTo(String.format("/%s/%s", testBucket, testKey)))
//             .withHeader("Range", equalTo(firstRangeHeader))
//             .willReturn(aResponse()
//                 .withStatus(400)
//                 .withBody("<Error><Code>InvalidRange</Code><Message>test error message</Message></Error>")));
//
//         AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
//         AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
//             SplittingTransformerConfiguration.builder()
//                                              .bufferSizeInBytes(1024 * 32L)
//                                              .build());
//
//         PresignedUrlDownloadRequest presignedRequest = PresignedUrlDownloadRequest.builder()
//             .presignedUrl(URI.create(String.format("http://localhost:%d/%s/%s",
//                 wiremock.getHttpPort(), testBucket, testKey)).toURL())
//             .build();
//
//         Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber =
//             new PresignedUrlMultipartDownloaderSubscriber(
//                 s3AsyncClient,
//                 presignedRequest,
//                 configuredPartSize);
//
//         split.publisher().subscribe(subscriber);
//         assertThatThrownBy(() -> split.resultFuture().join())
//             .hasMessageContaining("test error message");
//     }
//
//     @ParameterizedTest
//     @MethodSource("argumentsProvider")
//     <T> void errorOnThirdRequest_shouldCompleteExceptionallyOnlyForMultipart(
//         AsyncResponseTransformerTestSupplier<T> supplier,
//         int amountOfPartToTest,
//         int partSize) throws MalformedURLException {
//
//         if (amountOfPartToTest <= 2) {
//             // Skip test for cases with 2 or fewer parts - complete successfully instead
//             byte[] expectedBody = util.stubAllRangeParts(testBucket, testKey, amountOfPartToTest, partSize, testETag);
//
//             AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
//             AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
//                 SplittingTransformerConfiguration.builder()
//                                                  .bufferSizeInBytes(1024 * 32L)
//                                                  .build());
//
//             PresignedUrlDownloadRequest presignedRequest = PresignedUrlDownloadRequest.builder()
//                 .presignedUrl(URI.create(String.format("http://localhost:%d/%s/%s",
//                     wiremock.getHttpPort(), testBucket, testKey)).toURL())
//                 .build();
//
//             Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber =
//                 new PresignedUrlMultipartDownloaderSubscriber(
//                     s3AsyncClient,
//                     presignedRequest,
//                     partSize);
//
//             split.publisher().subscribe(subscriber);
//             T res = split.resultFuture().join();
//             byte[] body = supplier.body(res);
//             assertArrayEquals(expectedBody, body);
//             return;
//         }
//
//         // Stub first two range requests to succeed
//         util.stubForRangePart(testBucket, testKey, 0, objectSize, configuredPartSize, testETag); // First part
//         util.stubForRangePart(testBucket, testKey, 1, objectSize, configuredPartSize, testETag); // Second part
//
//         // Stub third range request to fail
//         long thirdPartStart = 2L * partSize;
//         long thirdPartEnd = Math.min(thirdPartStart + partSize - 1, (long) amountOfPartToTest * partSize - 1);
//         String thirdRangeHeader = String.format("bytes=%d-%d", thirdPartStart, thirdPartEnd);
//
//         stubFor(get(urlEqualTo(String.format("/%s/%s", testBucket, testKey)))
//             .withHeader("Range", equalTo(thirdRangeHeader))
//             .willReturn(aResponse()
//                 .withStatus(400)
//                 .withBody("<Error><Code>InvalidRange</Code><Message>test error message</Message></Error>")));
//
//         AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
//         AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
//             SplittingTransformerConfiguration.builder()
//                                              .bufferSizeInBytes(1024 * 32L)
//                                              .build());
//
//         PresignedUrlDownloadRequest presignedRequest = PresignedUrlDownloadRequest.builder()
//             .presignedUrl(URI.create(String.format("http://localhost:%d/%s/%s",
//                 wiremock.getHttpPort(), testBucket, testKey)).toURL())
//             .build();
//
//         Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber =
//             new PresignedUrlMultipartDownloaderSubscriber(
//                 s3AsyncClient,
//                 presignedRequest,
//                 partSize);
//
//         split.publisher().subscribe(subscriber);
//         assertThatThrownBy(() -> {
//             T res = split.resultFuture().join();
//             supplier.body(res);
//         }).hasMessageContaining("test error message");
//     }
//
//     private static Stream<Arguments> argumentsProvider() {
//         // For presigned URL multipart, we test different:
//         // - Object sizes (what's stored in S3)
//         // - Configured part sizes (what we choose for download)
//         List<Pair<Long, Long>> testCases = Arrays.asList(
//             // (objectSize, configuredPartSize)
//             Pair.of(1024L, 2048L),              // Small object, large part size (single part)
//             Pair.of(4096L, 1024L),              // 4KB object, 1KB parts (4 parts)
//             Pair.of(16L * 1024, 4L * 1024),     // 16KB object, 4KB parts (4 parts)
//             Pair.of(1024L * 1024, 256L * 1024), // 1MB object, 256KB parts (4 parts)
//             Pair.of(100L * 1024, 32L * 1024),   // 100KB object, 32KB parts (4 parts)
//             Pair.of(5L * 1024 * 1024, 1024L * 1024), // 5MB object, 1MB parts (5 parts)
//             Pair.of(1243L * 31, 1243L),         // ~38KB object, ~1.2KB parts (31 parts)
//             Pair.of(3752L * 7, 3752L),          // ~26KB object, ~3.7KB parts (7 parts)
//             Pair.of(8L * 1024 * 1024, 2L * 1024 * 1024) // 8MB object, 2MB parts (4 parts)
//         );
//
//         Stream.Builder<Arguments> sb = Stream.builder();
//         transformersSuppliers().forEach(tr ->
//             testCases.forEach(testCase ->
//                 sb.accept(arguments(tr, testCase.left(), testCase.right()))));
//         return sb.build();
//     }
// }
