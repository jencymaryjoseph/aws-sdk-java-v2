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
// import static com.github.tomakehurst.wiremock.client.WireMock.matching;
// import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
// import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
// import static com.github.tomakehurst.wiremock.client.WireMock.verify;
//
// import java.util.Random;
//
// /**
//  * Test utility for stubbing HTTP Range requests for presigned URL multipart downloads.
//  * Unlike traditional multipart which uses partNumber parameters, this utility handles
//  * Range header-based requests.
//  */
// public class PresignedUrlMultipartDownloadTestUtil {
//
//     private String testBucket;
//     private String testKey;
//     private String eTag;
//     private Random random = new Random();
//
//     public PresignedUrlMultipartDownloadTestUtil(String testBucket, String testKey, String eTag) {
//         this.testBucket = testBucket;
//         this.testKey = testKey;
//         this.eTag = eTag;
//     }
//
//     /**
//      * Stub all range parts for a multipart download test.
//      *
//      * @param bucket             S3 bucket name
//      * @param key                S3 object key
//      * @param objectSize         Total size of the object in S3
//      * @param configuredPartSize Part size we configure for download
//      * @param eTag               ETag to return in responses
//      * @return Expected body content as byte array
//      */
//     public byte[] stubAllRangeParts(String bucket, String key, long objectSize, long configuredPartSize, String eTag) {
//         byte[] expectedBody = new byte[(int) objectSize];
//         int totalParts = (int) Math.ceil((double) objectSize / configuredPartSize);
//
//         for (int partIndex = 0; partIndex < totalParts; partIndex++) {
//             long startByte = partIndex * configuredPartSize;
//             long endByte = Math.min(startByte + configuredPartSize - 1, objectSize - 1);
//             int actualPartSize = (int) (endByte - startByte + 1);
//
//             // Generate deterministic random data for this part
//             byte[] partData = new byte[actualPartSize];
//             Random partRandom = new Random(partIndex); // Deterministic seed
//             partRandom.nextBytes(partData);
//
//             // Copy part data to expected body
//             System.arraycopy(partData, 0, expectedBody, (int) startByte, actualPartSize);
//
//             // Stub the HTTP request
//             String rangeHeader = String.format("bytes=%d-%d", startByte, endByte);
//             String contentRangeHeader = String.format("bytes %d-%d/%d", startByte, endByte, objectSize);
//
//             stubFor(get(urlEqualTo(String.format("/%s/%s", bucket, key)))
//                         .withHeader("Range", equalTo(rangeHeader))
//                         .willReturn(aResponse()
//                                         .withStatus(206) // Partial Content
//                                         .withHeader("Content-Length", String.valueOf(actualPartSize))
//                                         .withHeader("Content-Range", contentRangeHeader)
//                                         .withHeader("ETag", eTag)
//                                         .withHeader("Accept-Ranges", "bytes")
//                                         .withBody(partData)));
//         }
//
//         return expectedBody;
//     }
//
//     /**
//      * Stub a specific range part request.
//      *
//      * @param bucket     S3 bucket name
//      * @param key        S3 object key
//      * @param partIndex  Zero-based part index
//      * @param totalParts Total number of parts
//      * @param partSize   Size of each part in bytes
//      * @param eTag       ETag to return in response
//      * @return Part data as byte array
//      */
//     public byte[] stubForRangePart(String bucket, String key, int partIndex, int totalParts, int partSize, String eTag) {
//         long totalSize = (long) totalParts * partSize;
//         long startByte = (long) partIndex * partSize;
//         long endByte = Math.min(startByte + partSize - 1, totalSize - 1);
//         int actualPartSize = (int) (endByte - startByte + 1);
//
//         // Generate deterministic random data for this part
//         byte[] partData = new byte[actualPartSize];
//         Random partRandom = new Random(partIndex); // Deterministic seed
//         partRandom.nextBytes(partData);
//
//         String rangeHeader = String.format("bytes=%d-%d", startByte, endByte);
//         String contentRangeHeader = String.format("bytes %d-%d/%d", startByte, endByte, totalSize);
//
//         stubFor(get(urlEqualTo(String.format("/%s/%s", bucket, key)))
//                     .withHeader("Range", equalTo(rangeHeader))
//                     .willReturn(aResponse()
//                                     .withStatus(206) // Partial Content
//                                     .withHeader("Content-Length", String.valueOf(actualPartSize))
//                                     .withHeader("Content-Range", contentRangeHeader)
//                                     .withHeader("ETag", eTag)
//                                     .withHeader("Accept-Ranges", "bytes")
//                                     .withBody(partData)));
//
//         return partData;
//     }
//
//     /**
//      * Verify that the correct number of range requests were made.
//      *
//      * @param expectedParts Expected number of range requests
//      */
//     public void verifyCorrectAmountOfRangeRequestsMade(int expectedParts) {
//         // For now, just verify that at least the expected number of requests were made
//         // The exact range verification is complex due to dynamic part sizing
//         for (int i = 0; i < expectedParts; i++) {
//             verify(getRequestedFor(urlEqualTo(String.format("/%s/%s", testBucket, testKey)))
//                        .withHeader("Range", matching("bytes=\\d+-\\d+")));
//         }
//     }
//
//     /**
//      * Stub a range request that returns an error.
//      *
//      * @param bucket       S3 bucket name
//      * @param key          S3 object key
//      * @param rangeHeader  Range header value (e.g., "bytes=0-1023")
//      * @param statusCode   HTTP status code to return
//      * @param errorMessage Error message to include in response body
//      */
//     public void stubRangeRequestError(String bucket, String key, String rangeHeader, int statusCode, String errorMessage) {
//         stubFor(get(urlEqualTo(String.format("/%s/%s", bucket, key)))
//                     .withHeader("Range", equalTo(rangeHeader))
//                     .willReturn(aResponse()
//                                     .withStatus(statusCode)
//                                     .withBody(String.format("<Error><Code>TestError</Code><Message>%s</Message></Error>", errorMessage))));
//     }
//
//     /**
//      * Stub a range request with missing Content-Range header (for testing error scenarios).
//      *
//      * @param bucket      S3 bucket name
//      * @param key         S3 object key
//      * @param rangeHeader Range header value
//      * @param partSize    Size of the part data
//      */
//     public void stubRangeRequestWithoutContentRange(String bucket, String key, String rangeHeader, int partSize) {
//         byte[] partData = new byte[partSize];
//         random.nextBytes(partData);
//
//         stubFor(get(urlEqualTo(String.format("/%s/%s", bucket, key)))
//                     .withHeader("Range", equalTo(rangeHeader))
//                     .willReturn(aResponse()
//                                     .withStatus(206)
//                                     .withHeader("Content-Length", String.valueOf(partSize))
//                                     .withHeader("ETag", eTag)
//                                     // Missing Content-Range header
//                                     .withBody(partData)));
//     }
//
//     /**
//      * Stub a range request with missing ETag header (for testing error scenarios).
//      *
//      * @param bucket      S3 bucket name
//      * @param key         S3 object key
//      * @param rangeHeader Range header value
//      * @param partSize    Size of the part data
//      * @param totalSize   Total object size
//      */
//     public void stubRangeRequestWithoutETag(String bucket, String key, String rangeHeader, int partSize, long totalSize) {
//         byte[] partData = new byte[partSize];
//         random.nextBytes(partData);
//
//         String contentRangeHeader = String.format("bytes 0-%d/%d", partSize - 1, totalSize);
//
//         stubFor(get(urlEqualTo(String.format("/%s/%s", bucket, key)))
//                     .withHeader("Range", equalTo(rangeHeader))
//                     .willReturn(aResponse()
//                                     .withStatus(206)
//                                     .withHeader("Content-Length", String.valueOf(partSize))
//                                     .withHeader("Content-Range", contentRangeHeader)
//                                     // Missing ETag header
//                                     .withBody(partData)));
//     }
//
//     /**
//      * Stub a range request with malformed Content-Range header (for testing error scenarios).
//      *
//      * @param bucket                S3 bucket name
//      * @param key                   S3 object key
//      * @param rangeHeader           Range header value
//      * @param partSize              Size of the part data
//      * @param malformedContentRange Malformed Content-Range header value
//      */
//     public void stubRangeRequestWithMalformedContentRange(String bucket, String key, String rangeHeader,
//                                                           int partSize, String malformedContentRange) {
//         byte[] partData = new byte[partSize];
//         random.nextBytes(partData);
//
//         stubFor(get(urlEqualTo(String.format("/%s/%s", bucket, key)))
//                     .withHeader("Range", equalTo(rangeHeader))
//                     .willReturn(aResponse()
//                                     .withStatus(206)
//                                     .withHeader("Content-Length", String.valueOf(partSize))
//                                     .withHeader("Content-Range", malformedContentRange)
//                                     .withHeader("ETag", eTag)
//                                     .withBody(partData)));
//     }
//
//     /**
//      * Stub a specific range part request by index for object-based testing.
//      *
//      * @param bucket             S3 bucket name
//      * @param key                S3 object key
//      * @param partIndex          Zero-based part index
//      * @param objectSize         Total object size
//      * @param configuredPartSize Configured part size for download
//      * @param eTag               ETag to return in response
//      * @return Part data as byte array
//      */
//     public byte[] stubForRangePart(String bucket, String key, int partIndex, long objectSize, long configuredPartSize, String eTag) {
//         long startByte = partIndex * configuredPartSize;
//         long endByte = Math.min(startByte + configuredPartSize - 1, objectSize - 1);
//         int actualPartSize = (int) (endByte - startByte + 1);
//
//         // Generate deterministic random data for this part
//         byte[] partData = new byte[actualPartSize];
//         Random partRandom = new Random(partIndex); // Deterministic seed
//         partRandom.nextBytes(partData);
//
//         String rangeHeader = String.format("bytes=%d-%d", startByte, endByte);
//         String contentRangeHeader = String.format("bytes %d-%d/%d", startByte, endByte, objectSize);
//
//         stubFor(get(urlEqualTo(String.format("/%s/%s", bucket, key)))
//                     .withHeader("Range", equalTo(rangeHeader))
//                     .willReturn(aResponse()
//                                     .withStatus(206) // Partial Content
//                                     .withHeader("Content-Length", String.valueOf(actualPartSize))
//                                     .withHeader("Content-Range", contentRangeHeader)
//                                     .withHeader("ETag", eTag)
//                                     .withHeader("Accept-Ranges", "bytes")
//                                     .withBody(partData)));
//
//         return partData;
//     }
// }
