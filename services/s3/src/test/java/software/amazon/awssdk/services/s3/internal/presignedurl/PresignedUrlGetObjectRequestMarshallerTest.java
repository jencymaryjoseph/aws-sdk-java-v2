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

import java.net.URI;
import java.net.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.internal.presignedurl.model.PresignedUrlGetObjectRequestWrapper;

class PresignedUrlGetObjectRequestMarshallerTest {

    private PresignedUrlGetObjectRequestMarshaller marshaller;
    private URL testUrl;

    @BeforeEach
    void setUp() throws Exception {
        marshaller = new PresignedUrlGetObjectRequestMarshaller();
        testUrl = new URL("https://test-bucket.s3.us-east-1.amazonaws.com/test-key?" +
                "X-Amz-Date=20231215T000000Z&" +
                "X-Amz-Signature=example-signature&" +
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&" +
                "X-Amz-SignedHeaders=host&" +
                "X-Amz-Security-Token=xxx&" +
                "X-Amz-Credential=EXAMPLE12345678901234%2F20231215%2Fus-east-1%2Fs3%2Faws4_request&" +
                "X-Amz-Expires=3600");
    }

    @Test
    void marshall_withBasicRequest_shouldCreateCorrectHttpRequest() throws Exception {
        PresignedUrlGetObjectRequestWrapper request = PresignedUrlGetObjectRequestWrapper.builder()
                .url(testUrl)
                .build();
        SdkHttpFullRequest result = marshaller.marshall(request);

        // Verify HTTP method and URI components
        assertThat(result.method()).isEqualTo(SdkHttpMethod.GET);
        assertThat(result.getUri())
                .satisfies(uri -> {
                    assertThat(uri.getScheme()).isEqualTo("https");
                    assertThat(uri.getHost()).isEqualTo("test-bucket.s3.us-east-1.amazonaws.com");
                    assertThat(uri.getPath()).isEqualTo("/test-key");
                });

        // Verify query parameters are preserved
        String query = result.getUri().getQuery();
        assertThat(query)
                .contains("X-Amz-Date=20231215T000000Z")
                .contains("X-Amz-Signature=example-signature")
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-SignedHeaders=host")
                .contains("X-Amz-Security-Token=xxx")
                .contains("X-Amz-Credential=EXAMPLE12345678901234")
                .contains("X-Amz-Expires=3600");
        
        assertThat(result.headers()).doesNotContainKey("Range");
    }

    @Test
    void marshall_withRangeVariations_shouldHandleCorrectly() throws Exception {
        // Test with valid range
        String rangeValue = "bytes=0-1023";
        PresignedUrlGetObjectRequestWrapper requestWithRange = PresignedUrlGetObjectRequestWrapper.builder()
                .url(testUrl)
                .range(rangeValue)
                .build();
        SdkHttpFullRequest resultWithRange = marshaller.marshall(requestWithRange);
        
        assertThat(resultWithRange.method()).isEqualTo(SdkHttpMethod.GET);
        assertThat(resultWithRange.getUri())
                .satisfies(uri -> {
                    assertThat(uri.getHost()).isEqualTo("test-bucket.s3.us-east-1.amazonaws.com");
                    assertThat(uri.getPath()).isEqualTo("/test-key");
                });
        assertThat(resultWithRange.headers())
                .containsKey("Range")
                .satisfies(headers -> assertThat(headers.get("Range")).contains(rangeValue));

        // Test with null and empty range - should not add Range header
        PresignedUrlGetObjectRequestWrapper requestWithNullRange = PresignedUrlGetObjectRequestWrapper.builder()
                .url(testUrl)
                .range(null)
                .build();
        PresignedUrlGetObjectRequestWrapper requestWithEmptyRange = PresignedUrlGetObjectRequestWrapper.builder()
                .url(testUrl)
                .range("")
                .build();
        
        assertThat(marshaller.marshall(requestWithNullRange).headers()).doesNotContainKey("Range");
        assertThat(marshaller.marshall(requestWithEmptyRange).headers()).doesNotContainKey("Range");
    }

    @Test
    void marshall_withNullRequest_shouldThrowException() {
        assertThatThrownBy(() -> marshaller.marshall(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("presignedUrlGetObjectRequestWrapper must not be null");
    }

    @Test
    void marshall_shouldPreserveUrlComponents() throws Exception {
        String originalUrlString = testUrl.toString();
        PresignedUrlGetObjectRequestWrapper request = PresignedUrlGetObjectRequestWrapper.builder()
                .url(testUrl)
                .range("bytes=0-100")
                .build();
        SdkHttpFullRequest result = marshaller.marshall(request);

        // Test URL preservation - original URL should not be modified
        assertThat(testUrl.toString()).isEqualTo(originalUrlString);
        
        // Test component preservation
        assertThat(result.getUri())
                .satisfies(uri -> {
                    assertThat(uri.getHost()).isEqualTo("test-bucket.s3.us-east-1.amazonaws.com");
                    assertThat(uri.getPath()).isEqualTo("/test-key");
                    assertThat(uri.getScheme()).isEqualTo("https");
                });
        
        // Test query parameter preservation
        assertThat(result.getUri().getQuery())
                .isNotNull()
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Signature=example-signature")
                .contains("X-Amz-Expires=3600");
    }

    @Test
    void marshall_withDifferentRangeFormats_shouldAddCorrectHeaders() throws Exception {
        String[] rangeFormats = {
                "bytes=0-100",      // First 101 bytes
                "bytes=100-",       // From byte 100 to end
                "bytes=-100",       // Last 100 bytes
                "bytes=0-0",        // Single byte
                "bytes=100-200"     // Specific range
        };

        for (String rangeFormat : rangeFormats) {
            PresignedUrlGetObjectRequestWrapper request = PresignedUrlGetObjectRequestWrapper.builder()
                    .url(testUrl)
                    .range(rangeFormat)
                    .build();
            SdkHttpFullRequest result = marshaller.marshall(request);

            assertThat(result.headers()).containsKey("Range");
            assertThat(result.headers().get("Range")).contains(rangeFormat);
        }
    }

    @Test
    void marshall_withMalformedUrl_shouldThrowSdkClientException() throws Exception {
        URL malformedUrl = new URL("https", "test-bucket.s3.us-east-1.amazonaws.com", -1, "/test key with spaces");
        PresignedUrlGetObjectRequestWrapper request = PresignedUrlGetObjectRequestWrapper.builder()
                .url(malformedUrl)
                .build();

        assertThatThrownBy(() -> marshaller.marshall(request))
                .isInstanceOf(SdkClientException.class)
                .hasMessageContaining("Unable to marshall pre-signed URL Request");
    }

    @Test
    void marshall_withComplexPresignedUrl_shouldPreserveAllParameters() throws Exception {
        URL complexUrl = new URL("https://my-bucket.s3.amazonaws.com/path/to/object.txt?" +
                "X-Amz-Date=20231215T120000Z&" +
                "X-Amz-Signature=example-signature-hash&" +
                "X-Amz-Algorithm=AWS4-HMAC-SHA256&" +
                "X-Amz-SignedHeaders=host%3Bx-amz-content-sha256&" +
                 "X-Amz-Security-Token=xxx&" +
                "X-Amz-Credential=EXAMPLE12345678901234%2F20231215%2Fus-east-1%2Fs3%2Faws4_request&" +
                "X-Amz-Expires=86400&" +
                "response-content-disposition=attachment%3B%20filename%3D%22download.txt%22");
        PresignedUrlGetObjectRequestWrapper request = PresignedUrlGetObjectRequestWrapper.builder()
                .url(complexUrl)
                .build();
        SdkHttpFullRequest result = marshaller.marshall(request);

        assertThat(result.getUri().getQuery())
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Credential=EXAMPLE12345678901234")
                .contains("X-Amz-Date=20231215T120000Z")
                .contains("X-Amz-Expires=86400")
                .contains("X-Amz-SignedHeaders=host")
                .contains("X-Amz-Security-Token=xxx")
                .contains("X-Amz-Signature=example-signature-hash")
                .contains("response-content-disposition=attachment");
    }
}
