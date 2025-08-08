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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static software.amazon.awssdk.services.s3.internal.multipart.MultipartDownloadTestUtil.transformersSuppliers;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SplittingTransformerConfiguration;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.services.s3.utils.AsyncResponseTransformerTestSupplier;
import software.amazon.awssdk.utils.Pair;

@WireMockTest
class PresignedUrlMultipartDownloaderSubscriberWiremockTest {

    private final String testBucket = "test-bucket";
    private final String testKey = "test-key";
    private final String basePresignedUrl = "http://localhost:%d/presigned-url";

    private S3AsyncClient s3AsyncClient;
    private PresignedUrlMultipartDownloadTestUtil util;

    @BeforeEach
    public void init(WireMockRuntimeInfo wiremock) {
        // Create S3AsyncClient with minimal configuration to avoid AWS SDK initialization issues
        s3AsyncClient = S3AsyncClient.builder()
                                     .credentialsProvider(StaticCredentialsProvider.create(
                                         AwsBasicCredentials.create("key", "secret")))
                                     .region(Region.US_WEST_2)
                                     .endpointOverride(URI.create("http://localhost:" + wiremock.getHttpPort()))
                                     .serviceConfiguration(S3Configuration.builder()
                                                                          .pathStyleAccessEnabled(true)
                                                                          .checksumValidationEnabled(false)
                                                                          .build())
                                     .build();
        util = new PresignedUrlMultipartDownloadTestUtil(
            String.format(basePresignedUrl, wiremock.getHttpPort()),
            UUID.randomUUID().toString()
        );
    }

    @ParameterizedTest
    @MethodSource("argumentsProvider")
    <T> void happyPath_withMultipleParts_shouldReceiveAllBodyPartsInCorrectOrder(
        AsyncResponseTransformerTestSupplier<T> supplier,
        int amountOfPartsToTest,
        int partSize) {
        
        // Arrange
        byte[] expectedBody = util.stubAllRangeParts(amountOfPartsToTest, partSize);
        URL presignedUrl = createPresignedUrl(util.getPresignedUrl());
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(presignedUrl)
                                          .build(),
                partSize);

        // Act
        split.publisher().subscribe(subscriber);
        T response = split.resultFuture().join();

        // Assert
        byte[] actualBody = supplier.body(response);
        assertArrayEquals(expectedBody, actualBody);
        util.verifyCorrectAmountOfRangeRequestsMade(amountOfPartsToTest);
    }

    @ParameterizedTest
    @MethodSource("singlePartArgumentsProvider")
    <T> void happyPath_withSinglePart_shouldReceiveCompleteBody(
        AsyncResponseTransformerTestSupplier<T> supplier,
        int partSize) {
        
        // Arrange
        int actualPartSize = partSize * 2; // Larger part size to ensure single part
        byte[] expectedBody = util.stubSingleRangePart(actualPartSize);
        URL presignedUrl = createPresignedUrl(util.getPresignedUrl());
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(presignedUrl)
                                          .build(),
                actualPartSize);

        // Act
        split.publisher().subscribe(subscriber);
        T response = split.resultFuture().join();

        // Assert
        byte[] actualBody = supplier.body(response);
        assertArrayEquals(expectedBody, actualBody);
        util.verifyCorrectAmountOfRangeRequestsMade(1);
    }

    @ParameterizedTest
    @MethodSource("argumentsProvider")
    <T> void errorOnFirstRequest_shouldCompleteExceptionally(
        AsyncResponseTransformerTestSupplier<T> supplier,
        int amountOfPartsToTest,
        int partSize) {
        
        // Arrange
        URL presignedUrl = createPresignedUrl(util.getPresignedUrl());
        util.stubFirstRangeRequestWithError();
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(presignedUrl)
                                          .build(),
                partSize);

        // Act & Assert
        split.publisher().subscribe(subscriber);
        assertThatThrownBy(() -> split.resultFuture().join())
            .hasMessageContaining("test error message");
    }

    @ParameterizedTest
    @MethodSource("argumentsProvider")
    <T> void errorOnSecondRequest_shouldCompleteExceptionally(
        AsyncResponseTransformerTestSupplier<T> supplier,
        int amountOfPartsToTest,
        int partSize) {
        
        // Arrange - only test when we have multiple parts
        if (amountOfPartsToTest < 2) {
            return;
        }
        
        URL presignedUrl = createPresignedUrl(util.getPresignedUrl());
        util.stubFirstRangePartForSizeDiscovery(amountOfPartsToTest, partSize);
        util.stubSecondRangeRequestWithError(partSize);
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(presignedUrl)
                                          .build(),
                partSize);

        // Act & Assert
        split.publisher().subscribe(subscriber);
        assertThatThrownBy(() -> split.resultFuture().get(10, TimeUnit.SECONDS))
            .hasMessageContaining("test error message");
    }

    @Test
    void constructor_withNullS3Client_shouldThrowException() {
        // Act & Assert
        assertThatThrownBy(() -> new PresignedUrlMultipartDownloaderSubscriber(
            null,
            PresignedUrlDownloadRequest.builder().presignedUrl(createPresignedUrl("http://example.com")).build(),
            1024))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("s3AsyncClient");
    }

    @Test
    void constructor_withNullRequest_shouldThrowException() {
        // Act & Assert
        assertThatThrownBy(() -> new PresignedUrlMultipartDownloaderSubscriber(
            s3AsyncClient,
            null,
            1024))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("presignedUrlDownloadRequest");
    }

    @Test
    void constructor_withNegativePartSize_shouldThrowException() {
        // Act & Assert
        assertThatThrownBy(() -> new PresignedUrlMultipartDownloaderSubscriber(
            s3AsyncClient,
            PresignedUrlDownloadRequest.builder().presignedUrl(createPresignedUrl("http://example.com")).build(),
            -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configuredPartSizeInBytes");
    }

    @Test
    void constructor_withZeroPartSize_shouldThrowException() {
        // Act & Assert
        assertThatThrownBy(() -> new PresignedUrlMultipartDownloaderSubscriber(
            s3AsyncClient,
            PresignedUrlDownloadRequest.builder().presignedUrl(createPresignedUrl("http://example.com")).build(),
            0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configuredPartSizeInBytes");
    }

    @ParameterizedTest
    @MethodSource("argumentsProvider")
    <T> void missingContentRangeHeader_shouldCompleteExceptionally(
        AsyncResponseTransformerTestSupplier<T> supplier,
        int amountOfPartsToTest,
        int partSize) {
        
        // Arrange
        URL presignedUrl = createPresignedUrl(util.getPresignedUrl());
        util.stubFirstRangeRequestWithoutContentRange(partSize);
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(presignedUrl)
                                          .build(),
                partSize);

        // Act & Assert
        split.publisher().subscribe(subscriber);
        assertThatThrownBy(() -> split.resultFuture().get(10, TimeUnit.SECONDS))
            .hasMessageContaining("No Content-Range header in response");
    }

    @ParameterizedTest
    @MethodSource("argumentsProvider")
    <T> void missingETagHeader_shouldCompleteExceptionally(
        AsyncResponseTransformerTestSupplier<T> supplier,
        int amountOfPartsToTest,
        int partSize) {
        
        // Arrange
        URL presignedUrl = createPresignedUrl(util.getPresignedUrl());
        util.stubFirstRangeRequestWithoutETag(amountOfPartsToTest, partSize);
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(presignedUrl)
                                          .build(),
                partSize);

        // Act & Assert
        split.publisher().subscribe(subscriber);
        assertThatThrownBy(() -> split.resultFuture().join())
            .hasMessageContaining("No ETag in response, cannot ensure consistency");
    }

    @ParameterizedTest
    @MethodSource("argumentsProvider")
    <T> void eTagMismatchBetweenParts_shouldCompleteExceptionally(
        AsyncResponseTransformerTestSupplier<T> supplier,
        int amountOfPartsToTest,
        int partSize) {
        
        // Arrange - only test when we have multiple parts
        if (amountOfPartsToTest < 2) {
            return;
        }
        
        URL presignedUrl = createPresignedUrl(util.getPresignedUrl());
        util.stubFirstRangePartForSizeDiscovery(amountOfPartsToTest, partSize);
        util.stubSecondRangePartWithDifferentETag(partSize);
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(presignedUrl)
                                          .build(),
                partSize);

        // Act & Assert
        split.publisher().subscribe(subscriber);
        assertThatThrownBy(() -> split.resultFuture().join())
            .hasMessageContaining("ETag mismatch - object may have changed during download");
    }

    private static Stream<Arguments> argumentsProvider() {
        // amount of parts, individual part size
        List<Pair<Integer, Integer>> partSizes = Arrays.asList(
            Pair.of(4, 16),
            Pair.of(1, 1024),
            Pair.of(31, 1243),
            Pair.of(16, 16 * 1024),
            Pair.of(1, 1024 * 1024),
            Pair.of(4, 1024 * 1024),
            Pair.of(1, 4 * 1024 * 1024),
            Pair.of(4, 6 * 1024 * 1024),
            Pair.of(7, 5 * 3752)
        );

        Stream.Builder<Arguments> sb = Stream.builder();
        transformersSuppliers().forEach(tr -> partSizes.forEach(p -> sb.accept(arguments(tr, p.left(), p.right()))));
        return sb.build();
    }

    private static Stream<Arguments> singlePartArgumentsProvider() {
        List<Integer> partSizes = Arrays.asList(16, 1024, 16 * 1024, 1024 * 1024, 4 * 1024 * 1024);

        Stream.Builder<Arguments> sb = Stream.builder();
        transformersSuppliers().forEach(tr -> partSizes.forEach(size -> sb.accept(arguments(tr, size))));
        return sb.build();
    }

    private URL createPresignedUrl(String urlString) {
        try {
            return new URL(urlString);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid presigned URL: " + urlString, e);
        }
    }
}
