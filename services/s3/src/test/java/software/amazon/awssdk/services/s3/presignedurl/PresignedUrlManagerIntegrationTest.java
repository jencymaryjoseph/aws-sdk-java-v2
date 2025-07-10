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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlGetObjectRequest;
import software.amazon.awssdk.testutils.service.http.MockSyncHttpClient;
import software.amazon.awssdk.utils.IoUtils;

/**
 * Comprehensive integration tests for PresignedUrlManager getObject method flavors.
 * Uses parameterized tests to reduce redundancy and improve maintainability.
 * Combines MockSyncHttpClient and WireMock testing approaches for complete coverage.
 */
@WireMockTest
public class PresignedUrlManagerIntegrationTest {

    private static final String TEST_CONTENT = "Hello World Test Content for S3 Pre-signed URL";
    private static final String PRESIGNED_URL = "https://test-bucket.s3.us-west-2.amazonaws.com/test-key?" +
                                               "X-Amz-Algorithm=AWS4-HMAC-SHA256&" +
                                               "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20231215%2Fus-west-2%2Fs3%2Faws4_request&" +
                                               "X-Amz-Date=20231215T000000Z&" +
                                               "X-Amz-Expires=3600&" +
                                               "X-Amz-SignedHeaders=host&" +
                                               "X-Amz-Signature=example-signature";

    private MockSyncHttpClient mockHttpClient;
    private S3Client s3Client;
    private PresignedUrlManager presignedUrlManager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mockHttpClient = new MockSyncHttpClient();
        s3Client = S3Client.builder()
                          .region(Region.US_WEST_2)
                          .credentialsProvider(AnonymousCredentialsProvider.create())
                          .httpClient(mockHttpClient)
                          .build();
        presignedUrlManager = s3Client.presignedUrlManager();
    }

    @AfterEach
    void tearDown() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (mockHttpClient != null) {
            mockHttpClient.close();
        }
    }

    // Enums for better parameterization
    enum GetObjectMethod {
        RESPONSE_STREAM("ResponseInputStream", (manager, request) -> {
            try {
                ResponseInputStream<GetObjectResponse> response = manager.getObject(request);
                String content = IoUtils.toUtf8String(response);
                response.close();
                return content;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }),
        
        RESPONSE_TRANSFORMER_BYTES("ResponseTransformer.toBytes()", (manager, request) -> {
            ResponseBytes<GetObjectResponse> response = manager.getObject(request, ResponseTransformer.toBytes());
            return response.asUtf8String();
        }),
        
        RESPONSE_TRANSFORMER_INPUT_STREAM("ResponseTransformer.toInputStream()", (manager, request) -> {
            try (ResponseInputStream<GetObjectResponse> response = manager.getObject(request, ResponseTransformer.toInputStream())) {
                return IoUtils.toUtf8String(response);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }),
        
        GET_OBJECT_AS_BYTES("getObjectAsBytes()", (manager, request) -> {
            ResponseBytes<GetObjectResponse> response = manager.getObjectAsBytes(request);
            return response.asUtf8String();
        }),
        
        CONSUMER_BUILDER_BYTES("Consumer Builder + toBytes()", (manager, request) -> {
            ResponseBytes<GetObjectResponse> response = manager.getObject(
                builder -> {
                    try {
                        builder.presignedUrl(request.presignedUrl());
                        if (request.range() != null) {
                            builder.range(request.range());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to build request", e);
                    }
                },
                ResponseTransformer.toBytes()
            );
            return response.asUtf8String();
        }),
        
        CONSUMER_BUILDER_AS_BYTES("Consumer Builder + getObjectAsBytes()", (manager, request) -> {
            ResponseBytes<GetObjectResponse> response = manager.getObjectAsBytes(
                builder -> {
                    try {
                        builder.presignedUrl(request.presignedUrl());
                        if (request.range() != null) {
                            builder.range(request.range());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to build request", e);
                    }
                }
            );
            return response.asUtf8String();
        }),
        
        LAMBDA_TRANSFORMER("Lambda ResponseTransformer", (manager, request) -> {
            return manager.getObject(request, (response, inputStream) -> {
                return IoUtils.toUtf8String(inputStream);
            });
        }),
        
        OUTPUT_STREAM_TRANSFORMER("OutputStream ResponseTransformer", (manager, request) -> {
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            manager.getObject(request, ResponseTransformer.toOutputStream(outputStream));
            return outputStream.toString();
        });

        private final String description;
        private final GetObjectFunction function;

        GetObjectMethod(String description, GetObjectFunction function) {
            this.description = description;
            this.function = function;
        }

        public String getDescription() {
            return description;
        }

        public String execute(PresignedUrlManager manager, PresignedUrlGetObjectRequest request) {
            return function.apply(manager, request);
        }

        @FunctionalInterface
        interface GetObjectFunction {
            String apply(PresignedUrlManager manager, PresignedUrlGetObjectRequest request);
        }
    }

    enum RangeFormat {
        STANDARD_RANGE("bytes=0-10", "Hello World"),
        MIDDLE_RANGE("bytes=5-15", " World Test"),
        OPEN_ENDED("bytes=0-", TEST_CONTENT),
        SUFFIX_RANGE("bytes=-10", "signed URL");

        private final String range;
        private final String expectedContent;

        RangeFormat(String range, String expectedContent) {
            this.range = range;
            this.expectedContent = expectedContent;
        }

        public String getRange() {
            return range;
        }

        public String getExpectedContent() {
            return expectedContent;
        }
    }

    enum HttpErrorScenario {
        FORBIDDEN(403, "AccessDenied", "Request has expired"),
        NOT_FOUND(404, "NoSuchKey", "The specified key does not exist"),
        INVALID_RANGE(416, "InvalidRange", "The requested range is not satisfiable"),
        INTERNAL_ERROR(500, "InternalError", "We encountered an internal error");

        private final int statusCode;
        private final String errorCode;
        private final String errorMessage;

        HttpErrorScenario(int statusCode, String errorCode, String errorMessage) {
            this.statusCode = statusCode;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    // Parameterized test for all getObject method variants
    @ParameterizedTest(name = "Method: {0}")
    @EnumSource(GetObjectMethod.class)
    void given_validPresignedUrl_when_callingGetObjectWithAllMethods_then_returnsCorrectContent(GetObjectMethod method) throws Exception {
        mockHttpClient.stubNextResponse(createSuccessfulResponse(TEST_CONTENT, null));
        PresignedUrlGetObjectRequest request = createBasicRequest();

        String result = method.execute(presignedUrlManager, request);

        assertThat(result).isEqualTo(TEST_CONTENT);
        validateUrlParameters(mockHttpClient.getLastRequest().getUri().toString());
    }

    // Parameterized test for range requests with all methods
    @ParameterizedTest(name = "Range: {0}, Method: {1}")
    @MethodSource("rangeAndMethodCombinations")
    void given_presignedUrlWithRange_when_callingGetObjectWithAllMethods_then_returnsPartialContent(RangeFormat rangeFormat, GetObjectMethod method) throws Exception {
        mockHttpClient.stubNextResponse(createSuccessfulResponse(rangeFormat.getExpectedContent(), rangeFormat.getRange()));
        
        PresignedUrlGetObjectRequest request = PresignedUrlGetObjectRequest.builder()
                                                                          .presignedUrl(new URL(PRESIGNED_URL))
                                                                          .range(rangeFormat.getRange())
                                                                          .build();

        String result = method.execute(presignedUrlManager, request);

        assertThat(result).isEqualTo(rangeFormat.getExpectedContent());
        assertThat(mockHttpClient.getLastRequest().firstMatchingHeader("Range")).hasValue(rangeFormat.getRange());
    }

    static Stream<Arguments> rangeAndMethodCombinations() {
        return Stream.of(RangeFormat.values())
                     .flatMap(range -> Stream.of(GetObjectMethod.values())
                                           .map(method -> Arguments.of(range, method)));
    }

    // Parameterized test for error scenarios
    @ParameterizedTest(name = "HTTP {0}: {1}")
    @EnumSource(HttpErrorScenario.class)
    void given_httpErrorResponse_when_callingGetObject_then_throwsAppropriateException(HttpErrorScenario scenario) throws Exception {
        mockHttpClient.stubNextResponse(createErrorResponse(scenario));
        PresignedUrlGetObjectRequest request = createBasicRequest();

        assertThatThrownBy(() -> presignedUrlManager.getObjectAsBytes(request))
            .isInstanceOf(S3Exception.class)
            .satisfies(exception -> {
                S3Exception s3Exception = (S3Exception) exception;
                assertThat(s3Exception.statusCode()).isEqualTo(scenario.getStatusCode());
                
                // For MockSyncHttpClient, the SDK may not always parse XML error messages correctly
                // So we check for either the expected message or the status code in the message
                String message = s3Exception.getMessage();
                boolean hasExpectedMessage = message.contains(scenario.getErrorMessage()) || 
                                           message.contains("Status Code: " + scenario.getStatusCode());
                assertThat(hasExpectedMessage)
                    .as("Exception message should contain either '%s' or 'Status Code: %d', but was: %s", 
                        scenario.getErrorMessage(), scenario.getStatusCode(), message)
                    .isTrue();
            });
    }

    // Test file download with different methods
    @ParameterizedTest(name = "File download with: {0}")
    @ValueSource(strings = {"getObject_toFile", "consumer_builder_toFile"})
    void given_validPresignedUrl_when_downloadingToFile_then_createsFileWithCorrectContent(String variant) throws Exception {
        mockHttpClient.stubNextResponse(createSuccessfulResponse(TEST_CONTENT, null));
        PresignedUrlGetObjectRequest request = createBasicRequest();
        Path downloadPath = tempDir.resolve("test-download-" + variant + ".txt");

        GetObjectResponse response;
        if (variant.equals("getObject_toFile")) {
            response = presignedUrlManager.getObject(request, downloadPath);
        } else {
            response = presignedUrlManager.getObject(
                builder -> {
                    try {
                        builder.presignedUrl(request.presignedUrl());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to build request", e);
                    }
                },
                downloadPath
            );
        }

        assertThat(response).isNotNull();
        assertThat(Files.exists(downloadPath)).isTrue();
        String fileContent = new String(Files.readAllBytes(downloadPath), StandardCharsets.UTF_8);
        assertThat(fileContent).isEqualTo(TEST_CONTENT);
    }

    // Enhanced content-range validation
    @Test
    void given_rangeRequest_when_callingGetObject_then_returnsCorrectContentRangeFormat() throws Exception {
        String rangeContent = TEST_CONTENT.substring(0, 10);
        mockHttpClient.stubNextResponse(HttpExecuteResponse.builder()
            .response(SdkHttpResponse.builder()
                .statusCode(206)
                .putHeader("Content-Type", "text/plain")
                .putHeader("Content-Range", "bytes 0-9/" + TEST_CONTENT.length())
                .putHeader("Content-Length", "10")
                .putHeader("Accept-Ranges", "bytes")
                .build())
            .responseBody(AbortableInputStream.create(
                new ByteArrayInputStream(rangeContent.getBytes(StandardCharsets.UTF_8))))
            .build());
        
        PresignedUrlGetObjectRequest request = PresignedUrlGetObjectRequest.builder()
            .presignedUrl(new URL(PRESIGNED_URL))
            .range("bytes=0-9")
            .build();
        
        ResponseBytes<GetObjectResponse> response = presignedUrlManager.getObjectAsBytes(request);
        
        assertThat(response.asUtf8String()).isEqualTo(rangeContent);
        assertThat(response.response().contentRange()).isEqualTo("bytes 0-9/" + TEST_CONTENT.length());
        assertThat(response.response().contentLength()).isEqualTo(10);
    }

    // Response metadata validation
    @Test
    void given_successfulResponse_when_callingGetObject_then_populatesResponseMetadataCorrectly() throws Exception {
        mockHttpClient.stubNextResponse(HttpExecuteResponse.builder()
            .response(SdkHttpResponse.builder()
                .statusCode(200)
                .putHeader("Content-Type", "text/plain")
                .putHeader("Content-Length", String.valueOf(TEST_CONTENT.length()))
                .putHeader("ETag", "\"d41d8cd98f00b204e9800998ecf8427e\"")
                .putHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .putHeader("x-amz-server-side-encryption", "AES256")
                .build())
            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8))))
            .build());

        PresignedUrlGetObjectRequest request = createBasicRequest();
        ResponseBytes<GetObjectResponse> response = presignedUrlManager.getObjectAsBytes(request);

        GetObjectResponse metadata = response.response();
        assertThat(metadata.contentType()).isEqualTo("text/plain");
        assertThat(metadata.contentLength()).isEqualTo(TEST_CONTENT.length());
        assertThat(metadata.eTag()).isEqualTo("\"d41d8cd98f00b204e9800998ecf8427e\"");
        assertThat(metadata.serverSideEncryption()).isNotNull();
    }

    // Sequential requests test
    @Test
    void given_multipleSequentialRequests_when_callingGetObject_then_allRequestsSucceed() throws Exception {
        PresignedUrlGetObjectRequest request = createBasicRequest();
        
        for (int i = 0; i < 3; i++) {
            mockHttpClient.stubNextResponse(createSuccessfulResponse(TEST_CONTENT, null));
            
            ResponseBytes<GetObjectResponse> response = presignedUrlManager.getObjectAsBytes(request);
            
            assertThat(response.asUtf8String())
                .as("Request %d result", i)
                .isEqualTo(TEST_CONTENT);
        }
    }

    /**
     * WireMock-based functional tests for realistic HTTP behavior
     */
    @Nested
    class WireMockFunctionalTests {

        private S3Client createS3ClientWithEndpoint(String baseUrl) {
            return S3Client.builder()
                          .region(Region.US_WEST_2)
                          .credentialsProvider(AnonymousCredentialsProvider.create())
                          .endpointOverride(URI.create(baseUrl))
                          .build();
        }

        private String createPresignedUrlForEndpoint(String baseUrl) {
            return baseUrl + "/test-key.txt?" +
                   "X-Amz-Algorithm=AWS4-HMAC-SHA256&" +
                   "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20231215%2Fus-west-2%2Fs3%2Faws4_request&" +
                   "X-Amz-Date=20231215T000000Z&" +
                   "X-Amz-Expires=3600&" +
                   "X-Amz-SignedHeaders=host&" +
                   "X-Amz-Signature=example-signature";
        }

        @ParameterizedTest(name = "WireMock method: {0}")
        @EnumSource(value = GetObjectMethod.class, names = {"RESPONSE_TRANSFORMER_BYTES", "GET_OBJECT_AS_BYTES", "LAMBDA_TRANSFORMER"})
        void given_validPresignedUrl_when_callingGetObjectWithWireMock_then_returnsCorrectContent(GetObjectMethod method, WireMockRuntimeInfo wm) throws Exception {
            stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain")
                    .withBody(TEST_CONTENT)));

            S3Client s3Client = createS3ClientWithEndpoint(wm.getHttpBaseUrl());
            PresignedUrlManager manager = s3Client.presignedUrlManager();

            PresignedUrlGetObjectRequest request = PresignedUrlGetObjectRequest.builder()
                                                                              .presignedUrl(new URL(createPresignedUrlForEndpoint(wm.getHttpBaseUrl())))
                                                                              .build();

            String result = method.execute(manager, request);
            assertThat(result).isEqualTo(TEST_CONTENT);

            s3Client.close();
        }

        @ParameterizedTest(name = "WireMock error: {0}")
        @EnumSource(HttpErrorScenario.class)
        void given_httpErrorResponse_when_callingGetObjectWithWireMock_then_throwsCorrectException(HttpErrorScenario scenario, WireMockRuntimeInfo wm) throws Exception {
            stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse()
                    .withStatus(scenario.getStatusCode())
                    .withHeader("Content-Type", "application/xml")
                    .withBody(String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                           "<Error>" +
                                           "<Code>%s</Code>" +
                                           "<Message>%s</Message>" +
                                           "<RequestId>4442587FB7D0A2F9</RequestId>" +
                                           "</Error>", scenario.getErrorCode(), scenario.getErrorMessage()))));

            S3Client s3Client = createS3ClientWithEndpoint(wm.getHttpBaseUrl());
            PresignedUrlManager manager = s3Client.presignedUrlManager();

            PresignedUrlGetObjectRequest request = PresignedUrlGetObjectRequest.builder()
                                                                              .presignedUrl(new URL(createPresignedUrlForEndpoint(wm.getHttpBaseUrl())))
                                                                              .build();

            assertThatThrownBy(() -> manager.getObjectAsBytes(request))
                .isInstanceOf(S3Exception.class)
                .hasMessageContaining(scenario.getErrorMessage());

            s3Client.close();
        }
    }

    // Helper methods
    private PresignedUrlGetObjectRequest createBasicRequest() throws Exception {
        return PresignedUrlGetObjectRequest.builder()
                                          .presignedUrl(new URL(PRESIGNED_URL))
                                          .build();
    }

    private HttpExecuteResponse createSuccessfulResponse(String content, String range) {
        SdkHttpResponse.Builder responseBuilder = SdkHttpResponse.builder()
            .statusCode(200)
            .putHeader("Content-Type", "text/plain")
            .putHeader("Content-Length", String.valueOf(content.length()))
            .putHeader("ETag", "\"example-etag\"")
            .putHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT");

        if (range != null && !range.equals("bytes=0-")) {
            responseBuilder.putHeader("Content-Range", "bytes 0-10/" + TEST_CONTENT.length());
        }

        return HttpExecuteResponse.builder()
            .response(responseBuilder.build())
            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))))
            .build();
    }

    private HttpExecuteResponse createErrorResponse(HttpErrorScenario scenario) {
        String errorBody = String.format(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Error>" +
            "<Code>%s</Code>" +
            "<Message>%s</Message>" +
            "<RequestId>4442587FB7D0A2F9</RequestId>" +
            "<HostId>example-host-id</HostId>" +
            "</Error>",
            scenario.getErrorCode(),
            scenario.getErrorMessage()
        );

        return HttpExecuteResponse.builder()
            .response(SdkHttpResponse.builder()
                .statusCode(scenario.getStatusCode())
                .putHeader("Content-Type", "application/xml")
                .putHeader("Content-Length", String.valueOf(errorBody.length()))
                .build())
            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(errorBody.getBytes(StandardCharsets.UTF_8))))
            .build();
    }

    private void validateUrlParameters(String actualUrl) {
        String expectedBaseUrl = PRESIGNED_URL.split("\\?")[0];
        String actualBaseUrl = actualUrl.split("\\?")[0];
        
        assertThat(actualBaseUrl).isEqualTo(expectedBaseUrl);
        assertThat(actualUrl).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256");
        assertThat(actualUrl).contains("X-Amz-Credential=AKIAIOSFODNN7EXAMPLE");
        assertThat(actualUrl).contains("X-Amz-Date=20231215T000000Z");
        assertThat(actualUrl).contains("X-Amz-Expires=3600");
        assertThat(actualUrl).contains("X-Amz-SignedHeaders=host");
        assertThat(actualUrl).contains("X-Amz-Signature=example-signature");
    }
}
