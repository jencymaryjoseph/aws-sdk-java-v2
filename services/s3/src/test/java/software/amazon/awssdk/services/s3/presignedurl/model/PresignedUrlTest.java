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

package software.amazon.awssdk.services.s3.presignedurl.model;

import java.net.URL;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlGetObjectRequest;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;


public class PresignedUrlTest {
    @Test
    void presignedUrlGetTest(){
        // Generate a presigned URL
        S3Presigner presigner = S3Presigner.builder()
                                           .build();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                                                            .bucket("jency-test-bucket")
                                                            .key("test1.txt")
                                                            .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                                                                        .signatureDuration(java.time.Duration.ofDays(5))
                                                                        .getObjectRequest(getObjectRequest)
                                                                        .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        URL presignedUrl = presignedRequest.url();

        S3Client s3Client = S3Client.builder().build();

        PresignedUrlGetObjectRequest request = PresignedUrlGetObjectRequest.builder()
                                                                           .presignedUrl(presignedUrl)
                                                                           .range("bytes=0-5")
                                                                           .build();
        // PresignedUrlGetObjectRequest request = PresignedUrlGetObjectRequest.builder(b -> b
        //     .presignedUrl(presignedUrl)
        //     .range("bytes=0-1023"));

        S3Client s3Client2 = S3Client.builder()
            .region(Region.US_WEST_2)
                                     .credentialsProvider(AnonymousCredentialsProvider.create())
                                     .build();

        ResponseBytes<GetObjectResponse> response = s3Client2
            .presignedUrlManager()
            .getObject(request, ResponseTransformer.toBytes());

        System.out.println("Content range: " + response.response().contentRange());

    }

}
