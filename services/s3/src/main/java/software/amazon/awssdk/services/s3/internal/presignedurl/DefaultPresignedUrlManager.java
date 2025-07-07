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

import static software.amazon.awssdk.core.client.config.SdkClientOption.SIGNER_OVERRIDDEN;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.awscore.internal.AwsProtocolMetadata;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.client.handler.ClientExecutionParams;
import software.amazon.awssdk.core.client.handler.SyncClientHandler;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.http.HttpResponseHandler;
import software.amazon.awssdk.core.interceptor.SdkInternalExecutionAttribute;
import software.amazon.awssdk.core.metrics.CoreMetric;
import software.amazon.awssdk.core.signer.NoOpSigner;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.metrics.MetricCollector;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.metrics.NoOpMetricCollector;
import software.amazon.awssdk.protocols.xml.AwsS3ProtocolFactory;
import software.amazon.awssdk.protocols.xml.XmlOperationMetadata;
import software.amazon.awssdk.services.s3.internal.presignedurl.model.PresignedUrlGetObjectRequestWrapper;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.InvalidObjectStateException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presignedurl.PresignedUrlManager;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlGetObjectRequest;

/**
 * Default implementation of {@link PresignedUrlManager} for executing S3 operations using presigned URLs.
 */
@SdkInternalApi
public final class DefaultPresignedUrlManager implements PresignedUrlManager {
    
    private final SyncClientHandler clientHandler;
    private final AwsS3ProtocolFactory protocolFactory;
    private final SdkClientConfiguration clientConfiguration;
    private final AwsProtocolMetadata protocolMetadata;
    
    public DefaultPresignedUrlManager(SyncClientHandler clientHandler, 
                                      AwsS3ProtocolFactory protocolFactory,
                                      SdkClientConfiguration clientConfiguration,
                                      AwsProtocolMetadata protocolMetadata) {
        this.clientHandler = clientHandler;
        this.protocolFactory = protocolFactory;
        this.clientConfiguration = clientConfiguration;
        this.protocolMetadata = protocolMetadata;
    }
    
    /**
     * <p>
     * Downloads an S3 object using a presigned URL.
     * </p>
     * <p>
     * This operation uses a presigned URL that contains all necessary authentication information, eliminating the need for AWS
     * credentials at request time. The presigned URL must be valid and not expired.
     * </p>
     * <p>
     * Supports partial object downloads using HTTP Range headers. Specify the range parameter
     * in the request to download only a portion of the object (e.g., "bytes=0-1023").
     * </p>
     *
     * @param presignedUrlGetObjectRequest
     *        The presigned URL request containing the URL and optional range parameters
     * @param responseTransformer
     *        Transforms the response to the desired return type. See
     *        {@link software.amazon.awssdk.core.sync.ResponseTransformer} for pre-built implementations like
     *        downloading to a file or converting to bytes.
     * @param <ReturnT>
     *        The type of the transformed response
     * @return The transformed result of the ResponseTransformer
     * @throws software.amazon.awssdk.services.s3.model.NoSuchKeyException
     *         The specified object does not exist
     * @throws software.amazon.awssdk.services.s3.model.InvalidObjectStateException
     *         Object is archived and must be restored before retrieval
     * @throws software.amazon.awssdk.core.exception.SdkClientException
     *         If any client side error occurs such as network failures, invalid presigned URL, or URL expiration
     * @throws S3Exception
     *         Base class for all S3 service exceptions. Unknown exceptions will be thrown as an
     *         instance of this type.
     * @sample S3Client.PresignedUrlManager.GetObject
     */
    @Override
    public <ReturnT> ReturnT getObject(PresignedUrlGetObjectRequest presignedUrlGetObjectRequest,
                                       ResponseTransformer<GetObjectResponse, ReturnT> responseTransformer) 
                                       throws NoSuchKeyException, InvalidObjectStateException, 
                                              AwsServiceException, SdkClientException, S3Exception {

        HttpResponseHandler<GetObjectResponse> responseHandler = protocolFactory.createResponseHandler(
                GetObjectResponse::builder, new XmlOperationMetadata().withHasStreamingSuccessResponse(true));

        HttpResponseHandler<AwsServiceException> errorResponseHandler = protocolFactory.createErrorResponseHandler();
        
        PresignedUrlGetObjectRequestWrapper internalRequest = PresignedUrlGetObjectRequestWrapper.builder()
                .url(presignedUrlGetObjectRequest.presignedUrl())
                .range(presignedUrlGetObjectRequest.range())
                .build();

        SdkClientConfiguration clientConfiguration = updateSdkClientConfiguration(internalRequest, this.clientConfiguration);
        List<MetricPublisher> metricPublishers = Optional.ofNullable(
            clientConfiguration.option(SdkClientOption.METRIC_PUBLISHERS))
            .orElse(Collections.emptyList());
        MetricCollector apiCallMetricCollector = metricPublishers.isEmpty() ?
            NoOpMetricCollector.create() : MetricCollector.create("ApiCall");
        try {
            apiCallMetricCollector.reportMetric(CoreMetric.SERVICE_ID, "S3");
            apiCallMetricCollector.reportMetric(CoreMetric.OPERATION_NAME, "GetObject");

            return clientHandler.execute(
                    new ClientExecutionParams<PresignedUrlGetObjectRequestWrapper, GetObjectResponse>()
                            .withOperationName("PresignedUrlGetObject")
                            .withProtocolMetadata(protocolMetadata)
                            .withResponseHandler(responseHandler)
                            .withErrorResponseHandler(errorResponseHandler)
                            .withRequestConfiguration(clientConfiguration)
                            .withInput(internalRequest)
                            .withMetricCollector(apiCallMetricCollector)
                            // TODO: Deprecate IS_DISCOVERED_ENDPOINT, use new SKIP_ENDPOINT_RESOLUTION for better semantics
                            .putExecutionAttribute(SdkInternalExecutionAttribute.IS_DISCOVERED_ENDPOINT, true)
                            .withMarshaller(new PresignedUrlGetObjectRequestMarshaller(protocolFactory)), responseTransformer);
        } finally {
            metricPublishers.forEach(p -> p.publish(apiCallMetricCollector.collect()));
        }
    }
    
    private SdkClientConfiguration updateSdkClientConfiguration(PresignedUrlGetObjectRequestWrapper request,
                                                                SdkClientConfiguration clientConfiguration) {
        SdkClientConfiguration.Builder configuration = clientConfiguration.toBuilder();
        configuration.option(SdkAdvancedClientOption.SIGNER, new NoOpSigner());
        configuration.option(SIGNER_OVERRIDDEN, true);
        return configuration.build();
    }

}
