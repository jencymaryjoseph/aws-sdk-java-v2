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
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import java.util.Random;

/**
 * Test utility class for PresignedUrlMultipartDownloaderSubscriber WireMock tests.
 * Provides methods to stub HTTP range requests and verify interactions.
 */
public class PresignedUrlMultipartDownloadTestUtil {

    private static final String PRESIGNED_URL_PATH = "/presigned-url";
    private static final String DIFFERENT_ETAG = "different-etag-12345";

    private final String presignedUrl;
    private final String eTag;
    private final Random random = new Random();

    public PresignedUrlMultipartDownloadTestUtil(String presignedUrl, String eTag) {
        this.presignedUrl = presignedUrl;
        this.eTag = eTag;
    }

    public String getPresignedUrl() {
        return presignedUrl;
    }

    /**
     * Stubs all range parts for a multipart download and returns the expected complete body.
     * Based on the error logs, the subscriber appears to use double the configured part size.
     * 
     * @param amountOfPartsToTest number of parts to create
     * @param partSize size of each part in bytes (configured part size)
     * @return the complete expected body as byte array
     */
    public byte[] stubAllRangeParts(int amountOfPartsToTest, int partSize) {
        // Use the configured part size directly
        int actualPartSize = partSize;
        byte[] expectedBody = new byte[amountOfPartsToTest * actualPartSize];
        random.nextBytes(expectedBody);
        
        long totalSize = expectedBody.length;
        
        // Create stubs for each expected range request
        for (int i = 0; i < amountOfPartsToTest; i++) {
            long startByte = i * actualPartSize;
            long endByte = Math.min(startByte + actualPartSize - 1, totalSize - 1);
            
            byte[] partBody = new byte[(int)(endByte - startByte + 1)];
            System.arraycopy(expectedBody, (int)startByte, partBody, 0, partBody.length);
            
            String rangeHeader = "bytes=" + startByte + "-" + endByte;
            String contentRange = "bytes " + startByte + "-" + endByte + "/" + totalSize;
            
            stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
                .withHeader("Range", new EqualToPattern(rangeHeader))
                .willReturn(aResponse()
                    .withStatus(206)
                    .withHeader("Content-Range", contentRange)
                    .withHeader("Content-Length", String.valueOf(partBody.length))
                    .withHeader("ETag", eTag)
                    .withBody(partBody)));
        }
        
        return expectedBody;
    }

    /**
     * Stubs a single range part for downloads that fit in one part.
     * 
     * @param partSize size of the single part in bytes
     * @return the expected body as byte array
     */
    public byte[] stubSingleRangePart(int partSize) {
        // For single part, use the actual configured size
        byte[] body = new byte[partSize];
        random.nextBytes(body);
        
        String rangeHeader = "bytes=0-" + (partSize - 1);
        String contentRange = "bytes 0-" + (partSize - 1) + "/" + partSize;
        
        stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
            .withHeader("Range", new EqualToPattern(rangeHeader))
            .willReturn(aResponse()
                .withStatus(206)
                .withHeader("Content-Range", contentRange)
                .withHeader("Content-Length", String.valueOf(partSize))
                .withHeader("ETag", eTag)
                .withBody(body)));
        
        return body;
    }

    /**
     * Stubs the first range part specifically for size discovery.
     * 
     * @param totalParts total number of parts in the object
     * @param partSize size of each part in bytes
     */
    public void stubFirstRangePartForSizeDiscovery(int totalParts, int partSize) {
        int actualPartSize = partSize;
        byte[] body = new byte[actualPartSize];
        random.nextBytes(body);
        
        long totalSize = totalParts * actualPartSize;
        String rangeHeader = "bytes=0-" + (actualPartSize - 1);
        String contentRange = "bytes 0-" + (actualPartSize - 1) + "/" + totalSize;
        
        stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
            .withHeader("Range", new EqualToPattern(rangeHeader))
            .atPriority(1)
            .willReturn(aResponse()
                .withStatus(206)
                .withHeader("Content-Range", contentRange)
                .withHeader("Content-Length", String.valueOf(actualPartSize))
                .withHeader("ETag", eTag)
                .withBody(body)));
    }

    /**
     * Stubs the first range request to return an error.
     */
    public void stubFirstRangeRequestWithError() {
        stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
            .atPriority(1)
            .willReturn(aResponse()
                .withStatus(400)
                .withBody("<Error><Code>400</Code><Message>test error message</Message></Error>")));
    }

    /**
     * Stubs the second range request to return an error.
     */
    public void stubSecondRangeRequestWithError() {
        stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
            .withHeader("Range", matching("bytes=(?!0-).*"))
            .atPriority(2)
            .willReturn(aResponse()
                .withStatus(400)
                .withBody("<Error><Code>400</Code><Message>test error message</Message></Error>")));
    }
    /**
     * Stubs the second range request to return an error for a specific part size.
     */
    public void stubSecondRangeRequestWithError(int partSize) {
        long startByte = partSize;
        long endByte = startByte + partSize - 1;
        String rangeHeader = "bytes=" + startByte + "-" + endByte;
        
        stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
            .withHeader("Range", new EqualToPattern(rangeHeader))
            .atPriority(2)
            .willReturn(aResponse()
                .withStatus(400)
                .withBody("<e><Code>400</Code><Message>test error message</Message></e>")));
    }
    /**
     * Stubs the first range request without Content-Range header.
     */
    public void stubFirstRangeRequestWithoutContentRange(int partSize) {
        int actualPartSize = partSize;
        byte[] body = new byte[actualPartSize];
        random.nextBytes(body);
        
        String rangeHeader = "bytes=0-" + (actualPartSize - 1);
        
        stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
            .withHeader("Range", new EqualToPattern(rangeHeader))
            .atPriority(1)
            .willReturn(aResponse()
                .withStatus(206)
                .withHeader("Content-Length", String.valueOf(actualPartSize))
                .withHeader("ETag", eTag)
                .withBody(body)));
    }

    /**
     * Stubs the first range request without ETag header.
     */
    public void stubFirstRangeRequestWithoutETag(int totalParts, int partSize) {
        int actualPartSize = partSize;
        byte[] body = new byte[actualPartSize];
        random.nextBytes(body);
        
        long totalSize = totalParts * actualPartSize;
        String rangeHeader = "bytes=0-" + (actualPartSize - 1);
        String contentRange = "bytes 0-" + (actualPartSize - 1) + "/" + totalSize;
        
        stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
            .withHeader("Range", new EqualToPattern(rangeHeader))
            .atPriority(1)
            .willReturn(aResponse()
                .withStatus(206)
                .withHeader("Content-Range", contentRange)
                .withHeader("Content-Length", String.valueOf(actualPartSize))
                .withBody(body)));
    }

    /**
     * Stubs the second range part with a different ETag.
     */
    public void stubSecondRangePartWithDifferentETag(int partSize) {
        int actualPartSize = partSize;
        byte[] body = new byte[actualPartSize];
        random.nextBytes(body);
        
        long startByte = actualPartSize;
        long endByte = startByte + actualPartSize - 1;
        String rangeHeader = "bytes=" + startByte + "-" + endByte;
        String contentRange = "bytes " + startByte + "-" + endByte + "/*";
        
        stubFor(get(urlEqualTo(PRESIGNED_URL_PATH))
            .withHeader("Range", new EqualToPattern(rangeHeader))
            .atPriority(2)
            .willReturn(aResponse()
                .withStatus(206)
                .withHeader("Content-Range", contentRange)
                .withHeader("Content-Length", String.valueOf(actualPartSize))
                .withHeader("ETag", DIFFERENT_ETAG)
                .withBody(body)));
    }

    /**
     * Verifies that the correct number of range requests were made to the presigned URL.
     */
    public void verifyCorrectAmountOfRangeRequestsMade(int expectedRequestCount) {
        verify(expectedRequestCount, getRequestedFor(urlEqualTo(PRESIGNED_URL_PATH)));
    }
}
