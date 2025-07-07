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

package software.amazon.awssdk.services.s3.internal.presignedurl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.awscore.internal.AwsProtocolMetadata;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.client.handler.ClientExecutionParams;
import software.amazon.awssdk.core.client.handler.SyncClientHandler;
import software.amazon.awssdk.core.http.HttpResponseHandler;
import software.amazon.awssdk.core.interceptor.SdkInternalExecutionAttribute;
import software.amazon.awssdk.core.signer.NoOpSigner;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.protocols.xml.AwsS3ProtocolFactory;
import software.amazon.awssdk.protocols.xml.XmlOperationMetadata;
import software.amazon.awssdk.services.s3.internal.presignedurl.model.PresignedUrlGetObjectRequestWrapper;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlGetObjectRequest;

@ExtendWith(MockitoExtension.class)
class DefaultPresignedUrlManagerTest {

    @Mock private SyncClientHandler clientHandler;
    @Mock private AwsS3ProtocolFactory protocolFactory;
    @Mock private SdkClientConfiguration clientConfiguration;
    @Mock private SdkClientConfiguration.Builder configurationBuilder;
    @Mock private AwsProtocolMetadata protocolMetadata;
    @Mock private HttpResponseHandler<GetObjectResponse> responseHandler;
    @Mock private HttpResponseHandler<AwsServiceException> errorResponseHandler;
    @Mock private GetObjectResponse mockResponse;

    private DefaultPresignedUrlManager presignedUrlManager;
    private URL testPresignedUrl;
    private PresignedUrlGetObjectRequest testRequest;

    @BeforeEach
    void setUp() throws Exception {
        testPresignedUrl = new URL("https://test-bucket.s3.us-east-1.amazonaws.com/test-key?" +
                "X-Amz-Date=20250707T000000Z&" +
                "X-Amz-Signature=test-signature-value&" +
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&" +
                "X-Amz-SignedHeaders=host&" +
                "X-Amz-Security-Token=test-session-token&" +
                "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20250707%2Fus-east-1%2Fs3%2Faws4_request&" +
                "X-Amz-Expires=86400");
        testRequest = PresignedUrlGetObjectRequest.builder()
                .presignedUrl(testPresignedUrl)
                .build();

        presignedUrlManager = new DefaultPresignedUrlManager(
                clientHandler, protocolFactory, clientConfiguration, protocolMetadata);
    }
    
    /**
     * Helper method to set up common mocks used across tests
     */
    private void setupCommonMocks() {
        when(clientConfiguration.toBuilder()).thenReturn(configurationBuilder);
        when(configurationBuilder.option(any(), any())).thenReturn(configurationBuilder);
        when(configurationBuilder.build()).thenReturn(clientConfiguration);
        when(protocolFactory.createResponseHandler(any(Supplier.class), any(XmlOperationMetadata.class))).thenReturn(responseHandler);
        when(protocolFactory.createErrorResponseHandler()).thenReturn(errorResponseHandler);
    }
    
    /**
     * Helper method to set up client handler mock with expected response
     */
    private <T> void setupClientHandlerMock(T expectedResponse) {
        when(clientHandler.<PresignedUrlGetObjectRequestWrapper, GetObjectResponse, T>execute(
                any(), any())).thenReturn(expectedResponse);
    }
    
    /**
     * Helper method to verify common parameters in ClientExecutionParams
     */
    private void verifyCommonParameters(ClientExecutionParams capturedParams) {
        assertThat(capturedParams.getOperationName()).isEqualTo("PresignedUrlGetObject");
        verify(configurationBuilder).option(eq(SdkAdvancedClientOption.SIGNER), any(NoOpSigner.class));
        verify(configurationBuilder).option(eq(SdkClientOption.SIGNER_OVERRIDDEN), eq(true));
        
        Optional<Boolean> skipEndpointResolution = capturedParams.executionAttributes()
            .getOptionalAttribute(SdkInternalExecutionAttribute.IS_DISCOVERED_ENDPOINT);
        assertThat(skipEndpointResolution).isPresent().contains(true);
    }

    /**
     * Test data for parameterized tests of different getObject flavors
     */
    private static Stream<Arguments> getObjectFlavorTestCases() {
        return Stream.of(
            Arguments.of(new Object[] {"String transformer", (Supplier<Object>) () -> mock(ResponseTransformer.class), (Supplier<Object>) () -> "test-response"})
        );
    }

    /**
     * Test data for parameterized tests of error cases
     */
    private static Stream<Arguments> errorCaseTestCases() {
        return Stream.of(
            Arguments.of(new Object[] {"Null request", true, false, NullPointerException.class}),
            Arguments.of(new Object[] {"Null transformer", false, true, NullPointerException.class})
        );
    }
    
    /**
     * Test data for parameterized tests of special request cases
     */
    private static Stream<Arguments> specialRequestTestCases() {
        return Stream.of(
            Arguments.of(new Object[] {"Consumer builder", createConsumerBuilderTestCase()}),
            Arguments.of(new Object[] {"Range header", createRangeHeaderTestCase()}),
            Arguments.of(new Object[] {"Response handler creation", createResponseHandlerTestCase()})
        );
    }
    
    private static Supplier<TestCase> createConsumerBuilderTestCase() {
        return () -> {
            try {
                URL url = new URL("https://test-bucket.s3.us-east-1.amazonaws.com/test-key?" +
                        "X-Amz-Date=20250707T000000Z&" +
                        "X-Amz-Signature=test-signature-value&" +
                        "X-Amz-Algorithm=AWS4-HMAC-SHA256&" +
                        "X-Amz-SignedHeaders=host&" +
                        "X-Amz-Security-Token=test-session-token&" +
                        "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20250707%2Fus-east-1%2Fs3%2Faws4_request&" +
                        "X-Amz-Expires=86400");
                return new TestCase(
                    (manager, req) -> manager.getObject(builder -> builder.presignedUrl(url).range("bytes=0-100")),
                    mock(ResponseInputStream.class),
                    params -> {
                        PresignedUrlGetObjectRequestWrapper request = (PresignedUrlGetObjectRequestWrapper) params.getInput();
                        assertThat(request.range()).isEqualTo("bytes=0-100");
                    }
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
    
    private static Supplier<TestCase> createRangeHeaderTestCase() {
        return () -> {
            try {
                URL url = new URL("https://test-bucket.s3.us-east-1.amazonaws.com/test-key?" +
                        "X-Amz-Date=20250707T000000Z&" +
                        "X-Amz-Signature=test-signature-value&" +
                        "X-Amz-Algorithm=AWS4-HMAC-SHA256&" +
                        "X-Amz-SignedHeaders=host&" +
                        "X-Amz-Security-Token=test-session-token&" +
                        "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20250707%2Fus-east-1%2Fs3%2Faws4_request&" +
                        "X-Amz-Expires=86400");
                PresignedUrlGetObjectRequest requestWithRange = PresignedUrlGetObjectRequest.builder()
                        .presignedUrl(url)
                        .range("bytes=0-1023")
                        .build();
                return new TestCase(
                    (manager, req) -> manager.getObject(requestWithRange),
                    mock(ResponseInputStream.class),
                    params -> {
                        PresignedUrlGetObjectRequestWrapper request = (PresignedUrlGetObjectRequestWrapper) params.getInput();
                        assertThat(request.range()).isEqualTo("bytes=0-1023");
                    }
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
    
    private static Supplier<TestCase> createResponseHandlerTestCase() {
        return () -> {
            ResponseTransformer<GetObjectResponse, String> responseTransformer = mock(ResponseTransformer.class);
            return new TestCase(
                (manager, req) -> {
                    manager.getObject(req, responseTransformer);
                    return "response";
                },
                "response",
                params -> {} // No additional verification needed
            );
        };
    }
    
    /**
     * Helper class to encapsulate test case data for special request tests
     */
    private static class TestCase {
        private final BiFunction<DefaultPresignedUrlManager, PresignedUrlGetObjectRequest, Object> testFunction;
        private final Object expectedResponse;
        private final Consumer<ClientExecutionParams> additionalVerification;
        
        TestCase(BiFunction<DefaultPresignedUrlManager, PresignedUrlGetObjectRequest, Object> testFunction,
                 Object expectedResponse,
                 Consumer<ClientExecutionParams> additionalVerification) {
            this.testFunction = testFunction;
            this.expectedResponse = expectedResponse;
            this.additionalVerification = additionalVerification;
        }
        
        Object executeTest(DefaultPresignedUrlManager manager, PresignedUrlGetObjectRequest request) {
            return testFunction.apply(manager, request);
        }
        
        Object getExpectedResponse() {
            return expectedResponse;
        }
        
        void verifyAdditional(ClientExecutionParams params) {
            additionalVerification.accept(params);
        }
    }
    
    /**
     * Functional interface for test functions that take two parameters
     */
    @FunctionalInterface
    private interface BiFunction<T, U, R> {
        R apply(T t, U u);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("getObjectFlavorTestCases")
    void testGetObjectFlavors(String testName, Supplier<Object> transformerSupplier, Supplier<Object> responseSupplier) {
        // Given
        Object expectedResponse = responseSupplier.get();
        Object transformer = transformerSupplier.get();
        
        setupCommonMocks();
        setupClientHandlerMock(expectedResponse);

        // When
        @SuppressWarnings("unchecked")
        ResponseTransformer<GetObjectResponse, Object> typedTransformer = 
            (ResponseTransformer<GetObjectResponse, Object>) transformer;
        Object result = presignedUrlManager.getObject(testRequest, typedTransformer);

        // Then
        assertThat(result).isEqualTo(expectedResponse);
        
        // Verify client handler was called with correct parameters
        ArgumentCaptor<ClientExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ClientExecutionParams.class);
        verify(clientHandler).execute(paramsCaptor.capture(), eq(typedTransformer));
        
        verifyCommonParameters(paramsCaptor.getValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorCaseTestCases")
    void testErrorCases(String testName, boolean isNullRequest, boolean isNullTransformer, Class<? extends Exception> expectedExceptionType) {
        // Given
        ResponseTransformer<GetObjectResponse, String> transformer = isNullTransformer ? null : mock(ResponseTransformer.class);
        PresignedUrlGetObjectRequest request = isNullRequest ? null : testRequest;
        
        // When/Then
        assertThatThrownBy(() -> presignedUrlManager.getObject(request, transformer))
                .isInstanceOf(expectedExceptionType);
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("specialRequestTestCases")
    void testSpecialRequestCases(String testName, Supplier<TestCase> testCaseSupplier) {
        // Given
        TestCase testCase = testCaseSupplier.get();
        
        setupCommonMocks();
        setupClientHandlerMock(testCase.getExpectedResponse());

        // When
        Object result = testCase.executeTest(presignedUrlManager, testRequest);

        // Then
        assertThat(result).isEqualTo(testCase.getExpectedResponse());
        
        // Verify client handler was called with correct parameters
        ArgumentCaptor<ClientExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ClientExecutionParams.class);
        verify(clientHandler).execute(paramsCaptor.capture(), any(ResponseTransformer.class));
        
        verifyCommonParameters(paramsCaptor.getValue());
        testCase.verifyAdditional(paramsCaptor.getValue());
        
        // For response handler creation test, verify protocol factory interactions
        if (testName.equals("Response handler creation")) {
            verify(protocolFactory).createResponseHandler(any(Supplier.class), any(XmlOperationMetadata.class));
            verify(protocolFactory).createErrorResponseHandler();
        }
    }
}
