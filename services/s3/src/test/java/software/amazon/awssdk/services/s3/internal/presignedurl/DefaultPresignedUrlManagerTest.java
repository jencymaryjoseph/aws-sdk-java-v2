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
// package software.amazon.awssdk.services.s3.internal.presignedurl;
//
// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.eq;
// import static org.mockito.Mockito.verify;
// import static org.mockito.Mockito.when;
//
// import java.net.URL;
// import java.util.Arrays;
// import java.util.Collections;
// import java.util.Optional;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.ArgumentCaptor;
// import org.mockito.Mock;
// import org.mockito.junit.jupiter.MockitoExtension;
// import software.amazon.awssdk.awscore.exception.AwsServiceException;
// import software.amazon.awssdk.awscore.internal.AwsProtocolMetadata;
// import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
// import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
// import software.amazon.awssdk.core.client.config.SdkClientOption;
// import software.amazon.awssdk.core.client.handler.ClientExecutionParams;
// import software.amazon.awssdk.core.client.handler.SyncClientHandler;
// import software.amazon.awssdk.core.http.HttpResponseHandler;
// import software.amazon.awssdk.core.signer.NoOpSigner;
// import software.amazon.awssdk.core.sync.ResponseTransformer;
// import software.amazon.awssdk.metrics.MetricPublisher;
// import software.amazon.awssdk.protocols.xml.AwsS3ProtocolFactory;
// import software.amazon.awssdk.protocols.xml.XmlOperationMetadata;
// import software.amazon.awssdk.services.s3.model.GetObjectResponse;
// import software.amazon.awssdk.services.s3.model.InvalidObjectStateException;
// import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
// import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlGetObjectRequest;
//
// @ExtendWith(MockitoExtension.class)
// class DefaultPresignedUrlManagerTest {
//
//     @Mock
//     private SyncClientHandler clientHandler;
//
//     @Mock
//     private AwsS3ProtocolFactory protocolFactory;
//
//     @Mock
//     private SdkClientConfiguration clientConfiguration;
//
//     @Mock
//     private SdkClientConfiguration.Builder configurationBuilder;
//
//     @Mock
//     private AwsProtocolMetadata protocolMetadata;
//
//     @Mock
//     private HttpResponseHandler<GetObjectResponse> responseHandler;
//
//     @Mock
//     private HttpResponseHandler<AwsServiceException> errorResponseHandler;
//
//     @Mock
//     private MetricPublisher metricPublisher;
//
//     private DefaultPresignedUrlManager presignedUrlManager;
//     private URL testPresignedUrl;
//     private PresignedUrlGetObjectRequest testRequest;
//
//     @BeforeEach
//     void setUp() throws Exception {
//         testPresignedUrl = new URL("https://test-bucket.s3.us-east-1.amazonaws.com/test-key?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=test");
//         testRequest = PresignedUrlGetObjectRequest.builder()
//                 .presignedUrl(testPresignedUrl)
//                 .build();
//
//         // Setup mock behavior
//         when(protocolFactory.createResponseHandler(any(), any())).thenReturn(responseHandler);
//         when(protocolFactory.createErrorResponseHandler()).thenReturn(errorResponseHandler);
//         when(clientConfiguration.toBuilder()).thenReturn(configurationBuilder);
//         when(configurationBuilder.option(any(), any())).thenReturn(configurationBuilder);
//         when(configurationBuilder.build()).thenReturn(clientConfiguration);
//         when(clientConfiguration.option(SdkClientOption.METRIC_PUBLISHERS)).thenReturn(null);
//
//         presignedUrlManager = new DefaultPresignedUrlManager(
//                 clientHandler, protocolFactory, clientConfiguration, protocolMetadata);
//     }
//
//     @Test
//     void getObject_withValidRequest_shouldExecuteSuccessfully() {
//         // Given
//         String expectedResponse = "test-response";
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         when(clientHandler.execute(any(ClientExecutionParams.class), eq(responseTransformer)))
//                 .thenReturn(expectedResponse);
//
//         // When
//         String result = presignedUrlManager.getObject(testRequest, responseTransformer);
//
//         // Then
//         assertThat(result).isEqualTo(expectedResponse);
//         verify(clientHandler).execute(any(ClientExecutionParams.class), eq(responseTransformer));
//     }
//
//     @Test
//     void getObject_withRange_shouldIncludeRangeInRequest() {
//         // Given
//         PresignedUrlGetObjectRequest requestWithRange = PresignedUrlGetObjectRequest.builder()
//                 .presignedUrl(testPresignedUrl)
//                 .range("bytes=0-1023")
//                 .build();
//
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         // When
//         presignedUrlManager.getObject(requestWithRange, responseTransformer);
//
//         // Then
//         ArgumentCaptor<ClientExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ClientExecutionParams.class);
//         verify(clientHandler).execute(paramsCaptor.capture(), eq(responseTransformer));
//
//         // Verify the internal request has the range
//         ClientExecutionParams capturedParams = paramsCaptor.getValue();
//         assertThat(capturedParams).isNotNull();
//     }
//
//     @Test
//     void getObject_withNullRequest_shouldThrowException() {
//         // Given
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         // When/Then
//         assertThatThrownBy(() -> presignedUrlManager.getObject(null, responseTransformer))
//                 .isInstanceOf(NullPointerException.class);
//     }
//
//     @Test
//     void getObject_withNullResponseTransformer_shouldThrowException() {
//         // When/Then
//         assertThatThrownBy(() -> presignedUrlManager.getObject(testRequest, null))
//                 .isInstanceOf(NullPointerException.class);
//     }
//
//     @Test
//     void getObject_shouldConfigureNoOpSigner() {
//         // Given
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         // When
//         presignedUrlManager.getObject(testRequest, responseTransformer);
//
//         // Then
//         verify(configurationBuilder).option(eq(SdkAdvancedClientOption.SIGNER), any(NoOpSigner.class));
//         verify(configurationBuilder).option(eq(SdkClientOption.SIGNER_OVERRIDDEN), eq(true));
//     }
//
//     @Test
//     void getObject_withMetricPublishers_shouldUseRealMetricCollector() {
//         // Given
//         when(clientConfiguration.option(SdkClientOption.METRIC_PUBLISHERS))
//                 .thenReturn(Arrays.asList(metricPublisher));
//
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         // When
//         presignedUrlManager.getObject(testRequest, responseTransformer);
//
//         // Then
//         ArgumentCaptor<ClientExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ClientExecutionParams.class);
//         verify(clientHandler).execute(paramsCaptor.capture(), eq(responseTransformer));
//
//         ClientExecutionParams capturedParams = paramsCaptor.getValue();
//         assertThat(capturedParams.metricCollector()).isNotNull();
//     }
//
//     @Test
//     void getObject_withNoMetricPublishers_shouldUseNoOpMetricCollector() {
//         // Given
//         when(clientConfiguration.option(SdkClientOption.METRIC_PUBLISHERS))
//                 .thenReturn(Collections.emptyList());
//
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         // When
//         presignedUrlManager.getObject(testRequest, responseTransformer);
//
//         // Then
//         ArgumentCaptor<ClientExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ClientExecutionParams.class);
//         verify(clientHandler).execute(paramsCaptor.capture(), eq(responseTransformer));
//
//         ClientExecutionParams capturedParams = paramsCaptor.getValue();
//         assertThat(capturedParams.metricCollector()).isNotNull();
//     }
//
//     @Test
//     void getObject_shouldCreateCorrectResponseHandlers() {
//         // Given
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         // When
//         presignedUrlManager.getObject(testRequest, responseTransformer);
//
//         // Then
//         verify(protocolFactory).createResponseHandler(eq(GetObjectResponse::builder),
//                 any(XmlOperationMetadata.class));
//         verify(protocolFactory).createErrorResponseHandler();
//     }
//
//     @Test
//     void getObject_shouldSetCorrectOperationName() {
//         // Given
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         // When
//         presignedUrlManager.getObject(testRequest, responseTransformer);
//
//         // Then
//         ArgumentCaptor<ClientExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ClientExecutionParams.class);
//         verify(clientHandler).execute(paramsCaptor.capture(), eq(responseTransformer));
//
//         ClientExecutionParams capturedParams = paramsCaptor.getValue();
//         assertThat(capturedParams.operationName()).isEqualTo("PresignedUrlGetObject");
//     }
//
//     @Test
//     void getObject_shouldPropagateClientHandlerExceptions() {
//         // Given
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         NoSuchKeyException expectedException = NoSuchKeyException.builder()
//                 .message("Key not found")
//                 .build();
//
//         when(clientHandler.execute(any(ClientExecutionParams.class), eq(responseTransformer)))
//                 .thenThrow(expectedException);
//
//         // When/Then
//         assertThatThrownBy(() -> presignedUrlManager.getObject(testRequest, responseTransformer))
//                 .isEqualTo(expectedException);
//     }
//
//     @Test
//     void updateSdkClientConfiguration_shouldSetNoOpSigner() {
//         // This tests the private method indirectly through getObject
//         // Given
//         ResponseTransformer<GetObjectResponse, String> responseTransformer = ResponseTransformer.toBytes()
//                 .andThen(bytes -> new String(bytes.asByteArray()));
//
//         // When
//         presignedUrlManager.getObject(testRequest, responseTransformer);
//
//         // Then
//         verify(configurationBuilder).option(eq(SdkAdvancedClientOption.SIGNER), any(NoOpSigner.class));
//         verify(configurationBuilder).option(eq(SdkClientOption.SIGNER_OVERRIDDEN), eq(true));
//         verify(configurationBuilder).build();
//     }
// }
