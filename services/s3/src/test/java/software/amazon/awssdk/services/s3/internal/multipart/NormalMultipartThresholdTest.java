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

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test to understand how normal object downloads behave with multipart configuration
 * when the object size is below the threshold.
 */
public class NormalMultipartThresholdTest {
    
    private static final String BUCKET = "jency-test-bucket";
    private static final String OBJECT_KEY = "Amazon Q.dmg"; // 155.3 MB file
    
    // Test different threshold scenarios
    private static final long PART_SIZE = 8L * 1024 * 1024; // 8MB
    private static final long HIGH_THRESHOLD = 1024L * 1024 * 1024; // 1GB (above file size)
    private static final long LOW_THRESHOLD = 9L * 1024 * 1024; // 9MB (below file size)
    private static final long DEFAULT_THRESHOLD = 16L * 1024 * 1024; // 16MB (below file size)

    public static void main(String[] args) throws Exception {
        System.out.println("🧪 Testing Normal Object Download Multipart Behavior");
        System.out.println("====================================================");
        System.out.println("📦 Bucket: " + BUCKET);
        System.out.println("🔑 Object: " + OBJECT_KEY);
        System.out.println("📏 Expected file size: ~155.3 MB");
        System.out.println();

        // Test 1: High threshold (file should be below threshold)
        System.out.println("🔬 TEST 1: High Threshold (1GB) - File Below Threshold");
        System.out.println("======================================================");
        testNormalDownloadWithThreshold(HIGH_THRESHOLD, "1GB threshold");
        
        System.out.println();
        
        // Test 2: Low threshold (file should be above threshold)  
        System.out.println("🔬 TEST 2: Low Threshold (1MB) - File Above Threshold");
        System.out.println("=====================================================");
        testNormalDownloadWithThreshold(LOW_THRESHOLD, "9MB threshold");
        
        System.out.println();
        
        // Test 3: Default threshold (file should be above threshold)
        System.out.println("🔬 TEST 3: Default Threshold (16MB) - File Above Threshold");
        System.out.println("==========================================================");
        testNormalDownloadWithThreshold(DEFAULT_THRESHOLD, "16MB threshold");
        
        System.out.println();
        System.out.println("🎯 Analysis Complete!");
        System.out.println("Compare the request patterns to understand how normal downloads handle thresholds.");
    }
    
    private static void testNormalDownloadWithThreshold(long threshold, String description) throws Exception {
        // Create request interceptor to track HTTP requests
        RequestCapture requestCapture = new RequestCapture();
        
        MultipartConfiguration config = MultipartConfiguration.builder()
            .minimumPartSizeInBytes(PART_SIZE)
            .thresholdInBytes(threshold)
            .build();

        S3AsyncClient client = S3AsyncClient.builder()
            .multipartConfiguration(config)
            .region(Region.US_EAST_2)
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(requestCapture)
                .build())
            .build();

        System.out.println("   Configuration:");
        System.out.println("   • Threshold: " + formatBytes(threshold));
        System.out.println("   • Part size: " + formatBytes(PART_SIZE));
        System.out.println("   • Client type: " + client.getClass().getSimpleName());
        
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(BUCKET)
            .key(OBJECT_KEY)
            .build();

        Instant start = Instant.now();
        
        try {
            // Perform the download - FIXED: No more var keyword
            ResponseBytes<GetObjectResponse> response = client.getObject(request, AsyncResponseTransformer.toBytes()).get();
            
            Duration elapsed = Duration.between(start, Instant.now());
            long fileSize = response.asByteArray().length;
            
            System.out.println("   ✅ Download completed in: " + elapsed.getSeconds() + " seconds");
            System.out.println("   📊 Downloaded: " + formatBytes(fileSize));
            
            // Analyze the requests
            List<String> requests = requestCapture.getCapturedRequests();
            System.out.println("   📡 HTTP Requests Made: " + requests.size());
            
            for (int i = 0; i < requests.size(); i++) {
                String rangeInfo = requests.get(i);
                System.out.println("   📡 Request #" + (i + 1) + ": " + rangeInfo);
            }
            
            // Analysis
            System.out.println("   🔍 Analysis:");
            if (fileSize < threshold) {
                System.out.println("   • File size (" + formatBytes(fileSize) + ") < threshold (" + formatBytes(threshold) + ")");
                if (requests.size() == 1) {
                    System.out.println("   ✅ Expected: Single request for file below threshold");
                } else {
                    System.out.println("   ⚠️  Unexpected: Multiple requests for file below threshold");
                }
            } else {
                System.out.println("   • File size (" + formatBytes(fileSize) + ") > threshold (" + formatBytes(threshold) + ")");
                if (requests.size() > 1) {
                    System.out.println("   ✅ Expected: Multiple requests for file above threshold");
                } else {
                    System.out.println("   ⚠️  Unexpected: Single request for file above threshold");
                }
            }
            
        } catch (Exception e) {
            System.out.println("   ❌ Download failed: " + e.getMessage());
        } finally {
            client.close();
        }
    }
    
    /**
     * Interceptor to capture HTTP requests and their Range headers
     */
    private static class RequestCapture implements ExecutionInterceptor {
        private final List<String> capturedRequests = new ArrayList<>();
        private final AtomicInteger requestCount = new AtomicInteger(0);

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
            SdkHttpRequest request = context.httpRequest();
            int requestNum = requestCount.incrementAndGet();
            
            // Check for Range header
            String rangeHeader = request.firstMatchingHeader("Range").orElse(null);
            String partNumber = request.firstMatchingHeader("x-amz-part-number").orElse(null);
            
            String rangeInfo;
            if (rangeHeader != null) {
                rangeInfo = "Range=" + rangeHeader;
            } else if (partNumber != null) {
                rangeInfo = "PartNumber=" + partNumber;
            } else {
                rangeInfo = "Range=none";
            }
            
            capturedRequests.add(rangeInfo);
        }

        public List<String> getCapturedRequests() {
            return new ArrayList<>(capturedRequests);
        }
    }
    
    /**
     * Format bytes in human readable format
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
