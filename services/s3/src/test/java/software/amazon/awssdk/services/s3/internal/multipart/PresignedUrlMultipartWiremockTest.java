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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.services.s3.internal.multipart.MultipartDownloadTestUtil.transformersSuppliers;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.core.SplittingTransformerConfiguration;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presignedurl.AsyncPresignedUrlExtension;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.services.s3.utils.AsyncResponseTransformerTestSupplier;
import software.amazon.awssdk.utils.Pair;

@WireMockTest
class PresignedUrlMultipartWiremockTest {

    private S3AsyncClient s3AsyncClient;
    private AsyncPresignedUrlExtension presignedUrlExtension;
    private String presignedUrl;
    private String eTag;

    @BeforeEach
    public void init(WireMockRuntimeInfo wiremock) {
        // Mock the S3AsyncClient and its presigned URL extension
        s3AsyncClient = Mockito.mock(S3AsyncClient.class);
        presignedUrlExtension = Mockito.mock(AsyncPresignedUrlExtension.class);
        when(s3AsyncClient.presignedUrlExtension()).thenReturn(presignedUrlExtension);
        
        presignedUrl = "http://localhost:" + wiremock.getHttpPort() + "/presigned-url";
        eTag = UUID.randomUUID().toString();
    }

    @ParameterizedTest
    @MethodSource("argumentsProvider")
    <T> void errorOnFirstRequest_shouldCompleteExceptionally(AsyncResponseTransformerTestSupplier<T> supplier,
                                                             int amountOfPartToTest,
                                                             int partSize) {
        // Mock first request to throw an exception with the expected error message
        CompletableFuture<T> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(SdkClientException.builder()
            .message("test error message")
            .build());
        
        when(presignedUrlExtension.getObject(any(PresignedUrlDownloadRequest.class), any(AsyncResponseTransformer.class)))
            .thenReturn(failedFuture);
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(createPresignedUrl(presignedUrl))
                                          .build(),
                partSize);

        split.publisher().subscribe(subscriber);
        assertThatThrownBy(() -> split.resultFuture().join())
            .hasMessageContaining("test error message");
    }

    @ParameterizedTest
    @MethodSource("argumentsProvider")
    <T> void errorOnSecondRequest_shouldCompleteExceptionallyOnlyPartsGreaterThanOne(
        AsyncResponseTransformerTestSupplier<T> supplier,
        int amountOfPartToTest,
        int partSize) {
        
        if (amountOfPartToTest < 2) {
            return; // Skip single part tests
        }
        
        // Mock first request to succeed (for size discovery)
        CompletableFuture<T> firstResponse = CompletableFuture.completedFuture(null); // Simplified response
        
        // Mock second request to fail
        CompletableFuture<T> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(SdkClientException.builder()
            .message("test error message")
            .build());
        
        when(presignedUrlExtension.getObject(any(PresignedUrlDownloadRequest.class), any(AsyncResponseTransformer.class)))
            .thenReturn(firstResponse)
            .thenReturn(failedFuture);
        
        AsyncResponseTransformer<GetObjectResponse, T> transformer = supplier.transformer();
        AsyncResponseTransformer.SplitResult<GetObjectResponse, T> split = transformer.split(
            SplittingTransformerConfiguration.builder()
                                             .bufferSizeInBytes(1024 * 32L)
                                             .build());
        
        Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> subscriber = 
            new PresignedUrlMultipartDownloaderSubscriber(
                s3AsyncClient,
                PresignedUrlDownloadRequest.builder()
                                          .presignedUrl(createPresignedUrl(presignedUrl))
                                          .build(),
                partSize);

        split.publisher().subscribe(subscriber);
        assertThatThrownBy(() -> {
            T res = split.resultFuture().join();
            supplier.body(res);
        }).hasMessageContaining("test error message");
    }

    private URL createPresignedUrl(String urlString) {
        try {
            return new URL(urlString);
        } catch (java.net.MalformedURLException e) {
            throw new RuntimeException("Invalid presigned URL: " + urlString, e);
        }
    }

    private static Stream<Arguments> argumentsProvider() {
        // Simplified argument provider focusing on error cases
        List<Pair<Integer, Integer>> partSizes = Arrays.asList(
            Pair.of(1, 1024),
            Pair.of(2, 1024),
            Pair.of(4, 1024)
        );

        Stream.Builder<Arguments> sb = Stream.builder();
        transformersSuppliers().forEach(tr -> partSizes.forEach(p -> sb.accept(arguments(tr, p.left(), p.right()))));
        return sb.build();
    }
}
