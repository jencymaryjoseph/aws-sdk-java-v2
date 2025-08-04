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
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presignedurl.model.PresignedUrlDownloadRequest;
import software.amazon.awssdk.regions.Region;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class PresignedUrlMultipartDemo {
    
    private static final String BUCKET_NAME = "jency-test-bucket";
    private static final String OBJECT_KEY = "Amazon Q.dmg";
    
    // Test configuration
    private static final long PART_SIZE = 8L * 1024 * 1024; // 8MB
    private static final long THRESHOLD = 16L * 1024 * 1024; // 16MB
    
    public static void main(String[] args) throws Exception {
        System.out.println("🎬 Starting S3 Presigned URL Multipart Download Demo & Tests");
        System.out.println("==============================================================");
        System.out.println("📦 Bucket: " + BUCKET_NAME);
        System.out.println("🔑 Object: " + OBJECT_KEY);
        System.out.println();
        
        // Enable debug logging for multipart components
        enableMultipartLogging();
        
        // Generate presigned URL once for all tests
        URL presignedUrl = generatePresignedUrl();
        
        // Run comprehensive tests
        runAllTests(presignedUrl);
        
        System.out.println("\n🎉 All Tests Complete!");
    }
    
    /**
     * Enable debug logging to see multipart behavior
     */
    private static void enableMultipartLogging() {
        System.setProperty("org.slf4j.simpleLogger.log.software.amazon.awssdk.services.s3.internal.multipart", "DEBUG");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
        System.out.println("🔍 Debug logging enabled for multipart components");
        System.out.println();
    }
    
    /**
     * Run all test scenarios
     */
    private static void runAllTests(URL presignedUrl) throws Exception {
        // NEW: Test normal object downloads first
        testNormalObjectDownloads();
        
        // Test 1: Basic functionality comparison
        System.out.println("🧪 TEST 1: Basic Functionality Comparison");
        System.out.println("==========================================");
        demonstrateTraditionalDownload(presignedUrl);
        System.out.println();
        demonstrateMultipartDownloadWithVerification(presignedUrl);
        
        System.out.println("\n" + "============================================================");
        
        // Test 2: Verify multipart behavior with request tracking
        System.out.println("🧪 TEST 2: Multipart Behavior Verification");
        System.out.println("==========================================");
        testMultipartBehaviorWithRequestTracking(presignedUrl);
        
        System.out.println("\n" + "============================================================");
        
        // Test 3: Test different file size scenarios
        System.out.println("🧪 TEST 3: File Size Scenarios");
        System.out.println("==============================");
        testDifferentFileSizeScenarios();
        
        System.out.println("\n" + "============================================================");
        
        // Test 4: Error handling scenarios
        System.out.println("🧪 TEST 4: Error Handling");
        System.out.println("=========================");
        testErrorHandlingScenarios();
        
        System.out.println("\n" + "============================================================");
        
        // Test 5: Performance comparison
        System.out.println("🧪 TEST 5: Performance Analysis");
        System.out.println("===============================");
        performanceComparisonTest(presignedUrl);
    }
    
    /**
     * Generate a presigned URL for our demo object
     */
    private static URL generatePresignedUrl() {
        try (S3Presigner presigner = S3Presigner.create()) {
            // Define the S3 object request
            GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(OBJECT_KEY)
                .build();
            
            // Create the presign request
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10)) // URL expires in 10 minutes
                .getObjectRequest(objectRequest)
                .build();
            
            // Generate the presigned URL
            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            
            // Retrieve the URL
            URL presignedUrl = presignedRequest.url();
            System.out.println("🔗 Generated presigned URL: " + presignedUrl.toExternalForm().substring(0, 100) + "...");
            System.out.println("⏰ URL expires in 10 minutes");
            System.out.println();
            
            return presignedUrl;
        }
    }
    
    /**
     * 📊 Presigned URL Single-Stream Download
     * Downloads entire file in one stream using presigned URLs
     */
    private static void demonstrateTraditionalDownload(URL presignedUrl) throws Exception {
        System.out.println("📥 DEMO 1: Presigned URL Single-Stream Download");
        System.out.println("   → Downloads entire file in one request using presigned URL");
        System.out.println("   → Using same presigned URL for fair comparison");
        
        // Standard S3 client - no multipart configuration
        S3AsyncClient standardClient = S3AsyncClient.builder()
            .region(Region.US_EAST_1)
            .build();
        
        Instant start = Instant.now();
        System.out.println("   ⏱️  Starting download at: " + start);
        
        // Single stream presigned URL download
        PresignedUrlDownloadRequest request = PresignedUrlDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .build();
        
        System.out.println("   🔍 Single-stream approach:");
        System.out.println("      • Single HTTP connection to presigned URL");
        System.out.println("      • Downloads entire file sequentially");
        System.out.println("      • Limited by single connection bandwidth");
        
        try {
            // Execute download
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                standardClient.presignedUrlExtension()
                             .getObject(request, AsyncResponseTransformer.toBytes());
            
            ResponseBytes<GetObjectResponse> response = future.get();
            
            Duration elapsed = Duration.between(start, Instant.now());
            long seconds = elapsed.getSeconds();
            long bytes = response.asByteArray().length;
            
            System.out.println("   ✅ Download completed in: " + seconds + " seconds");
            System.out.println("   📊 Downloaded: " + formatBytes(bytes));
            System.out.println("   📊 Throughput: " + formatThroughput(bytes, seconds));
            
        } catch (Exception e) {
            System.out.println("   ❌ Download failed: " + e.getMessage());
            throw e;
        } finally {
            standardClient.close();
        }
    }

    private static void demonstrateMultipartDownload(URL presignedUrl) throws Exception {
        System.out.println("🔥 DEMO 2: Presigned URL Multipart Download (Key Innovation!)");

        MultipartConfiguration config = MultipartConfiguration.builder()
            .minimumPartSizeInBytes(PART_SIZE)
            .thresholdInBytes(THRESHOLD)
            .build();

        S3AsyncClient multipartClient = S3AsyncClient.builder()
            .multipartConfiguration(config)
            
            .region(Region.US_EAST_1)
            .build();
        
        Instant start = Instant.now();
        System.out.println("   ⏱️  Starting multipart download at: " + start);

        PresignedUrlDownloadRequest request = PresignedUrlDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .build();

        System.out.println("   🔍 Multipart innovation for presigned URLs:");
        System.out.println("      • Step 1: First part request (bytes=0-8388607) downloads 8MB + discovers total size");
        System.out.println("      • Step 2: Parse Content-Range header to get total object size");
        System.out.println("      • Step 3: Calculate remaining parts needed for parallel download");
        System.out.println("      • Step 4: Generate Range headers for remaining parts (bytes=8388608-16777215...)");
        System.out.println("      • Step 5: Download remaining parts concurrently while processing first part");
        System.out.println("      • Step 6: Assemble all parts in correct order");
        
        try {
            // Execute multipart download
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                multipartClient.presignedUrlExtension()
                              .getObject(request, AsyncResponseTransformer.toBytes());
            
            ResponseBytes<GetObjectResponse> response = future.get();
            
            Duration elapsed = Duration.between(start, Instant.now());
            long seconds = elapsed.getSeconds();
            long bytes = response.asByteArray().length;
            
            System.out.println("   ✅ Download completed in: " + seconds + " seconds");
            System.out.println("   📊 Downloaded: " + formatBytes(bytes));
            System.out.println("   📊 Throughput: " + formatThroughput(bytes, seconds));
            System.out.println("   ⚡ Multiple parallel connections used automatically");
            
            System.out.println("   🚀 Key innovation: Multipart downloads now work with presigned URLs!");
            
        } catch (Exception e) {
            System.out.println("   ❌ Multipart download failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            multipartClient.close();
        }
    }
    
    /**
     * Enhanced multipart download with verification
     */
    private static void demonstrateMultipartDownloadWithVerification(URL presignedUrl) throws Exception {
        System.out.println("🔥 ENHANCED: Presigned URL Multipart Download with Verification");

        MultipartConfiguration config = MultipartConfiguration.builder()
            .minimumPartSizeInBytes(PART_SIZE)
            .thresholdInBytes(THRESHOLD)
            .build();

        S3AsyncClient multipartClient = S3AsyncClient.builder()
            .multipartConfiguration(config)
            
            .region(Region.US_EAST_1)
            .build();
        
        Instant start = Instant.now();
        System.out.println("   ⏱️  Starting verified multipart download at: " + start);

        PresignedUrlDownloadRequest request = PresignedUrlDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .build();
        
        try {
            // Execute multipart download
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                multipartClient.presignedUrlExtension()
                              .getObject(request, AsyncResponseTransformer.toBytes());
            
            ResponseBytes<GetObjectResponse> response = future.get();
            
            Duration elapsed = Duration.between(start, Instant.now());
            long seconds = elapsed.getSeconds();
            long bytes = response.asByteArray().length;
            
            System.out.println("   ✅ Download completed in: " + seconds + " seconds");
            System.out.println("   📊 Downloaded: " + formatBytes(bytes));
            System.out.println("   📊 Throughput: " + formatThroughput(bytes, seconds));
            
            // Verify file size suggests multipart was used
            if (bytes > THRESHOLD) {
                System.out.println("   ✅ File size (" + formatBytes(bytes) + ") > threshold (" + formatBytes(THRESHOLD) + ") - multipart should have been used");
            } else {
                System.out.println("   ⚠️  File size (" + formatBytes(bytes) + ") < threshold (" + formatBytes(THRESHOLD) + ") - single stream likely used");
            }
            
        } catch (Exception e) {
            System.out.println("   ❌ Multipart download failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            multipartClient.close();
        }
    }
    
    /**
     * Test multipart behavior with HTTP request tracking
     */
    private static void testMultipartBehaviorWithRequestTracking(URL presignedUrl) throws Exception {
        System.out.println("🔍 Testing multipart behavior with HTTP request tracking...");
        
        // List to capture all HTTP requests
        List<HttpRequestInfo> httpRequests = new ArrayList<>();
        AtomicInteger requestCounter = new AtomicInteger(0);
        
        // Custom interceptor to capture HTTP requests
        ExecutionInterceptor requestCapture = new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
                SdkHttpRequest request = context.httpRequest();
                String rangeHeader = request.firstMatchingHeader("Range").orElse("none");
                String contentRange = request.firstMatchingHeader("Content-Range").orElse("none");
                
                HttpRequestInfo info = new HttpRequestInfo(
                    requestCounter.incrementAndGet(),
                    request.getUri().toString(),
                    rangeHeader,
                    contentRange,
                    System.currentTimeMillis()
                );
                
                httpRequests.add(info);
                System.out.println("   📡 Request #" + info.requestNumber + ": Range=" + rangeHeader);
            }
        };
        
        MultipartConfiguration config = MultipartConfiguration.builder()
            .minimumPartSizeInBytes(PART_SIZE)
            .thresholdInBytes(THRESHOLD)
            .build();

        S3AsyncClient multipartClient = S3AsyncClient.builder()
            .multipartConfiguration(config)
            
            .region(Region.US_EAST_1)
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(requestCapture)
                .build())
            .build();
        
        PresignedUrlDownloadRequest request = PresignedUrlDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .build();
        
        Instant start = Instant.now();
        
        try {
            // Execute download with request tracking
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                multipartClient.presignedUrlExtension()
                              .getObject(request, AsyncResponseTransformer.toBytes());
            
            ResponseBytes<GetObjectResponse> response = future.get();
            
            Duration elapsed = Duration.between(start, Instant.now());
            long bytes = response.asByteArray().length;
            
            // Analyze captured requests
            System.out.println("\n   📊 HTTP Request Analysis:");
            System.out.println("   ========================");
            System.out.println("   Total requests made: " + httpRequests.size());
            System.out.println("   File size: " + formatBytes(bytes));
            System.out.println("   Download time: " + elapsed.getSeconds() + " seconds");
            
            // Verify multipart behavior
            verifyMultipartBehavior(httpRequests, bytes);
            
        } catch (Exception e) {
            System.out.println("   ❌ Request tracking test failed: " + e.getMessage());
            throw e;
        } finally {
            multipartClient.close();
        }
    }
    
    /**
     * Verify that multipart behavior occurred based on HTTP requests
     */
    private static void verifyMultipartBehavior(List<HttpRequestInfo> requests, long totalBytes) {
        System.out.println("\n   🔍 Verifying multipart behavior:");
        
        if (requests.isEmpty()) {
            System.out.println("   ❌ No HTTP requests captured!");
            return;
        }
        
        // Print all requests for debugging
        for (HttpRequestInfo req : requests) {
            System.out.println("   📡 Request #" + req.requestNumber + ": " + req.rangeHeader);
        }
        
        if (totalBytes > THRESHOLD) {
            // File is large enough for multipart
            if (requests.size() == 1) {
                System.out.println("   ⚠️  WARNING: Only 1 request for large file - multipart may not have been used");
                System.out.println("       This could indicate:");
                System.out.println("       • Size discovery failed");
                System.out.println("       • Fallback to single-stream occurred");
                System.out.println("       • Multipart threshold not met");
            } else {
                System.out.println("   ✅ VERIFIED: " + requests.size() + " requests made for large file - multipart used!");
                
                // Verify first request is size discovery
                HttpRequestInfo firstRequest = requests.get(0);
                if (firstRequest.rangeHeader.startsWith("bytes=0-")) {
                    System.out.println("   ✅ VERIFIED: First request uses range header for size discovery");
                } else if (firstRequest.rangeHeader.equals("none")) {
                    System.out.println("   ⚠️  First request has no range header - may be single stream");
                } else {
                    System.out.println("   ⚠️  Unexpected first request range: " + firstRequest.rangeHeader);
                }
                
                // Verify subsequent requests have different ranges
                if (requests.size() > 1) {
                    boolean hasVariedRanges = requests.stream()
                        .map(r -> r.rangeHeader)
                        .distinct()
                        .count() > 1;
                    
                    if (hasVariedRanges) {
                        System.out.println("   ✅ VERIFIED: Multiple different range requests - parallel download confirmed");
                    } else {
                        System.out.println("   ⚠️  All requests have same range - unexpected behavior");
                    }
                }
            }
        } else {
            // File is small - should use single stream
            if (requests.size() == 1) {
                System.out.println("   ✅ VERIFIED: Single request for small file - correct single-stream behavior");
            } else {
                System.out.println("   ⚠️  Multiple requests for small file - unexpected multipart usage");
            }
        }
        
        // Calculate estimated parts
        long estimatedParts = (long) Math.ceil((double) totalBytes / PART_SIZE);
        System.out.println("   📊 File analysis:");
        System.out.println("       • File size: " + formatBytes(totalBytes));
        System.out.println("       • Part size: " + formatBytes(PART_SIZE));
        System.out.println("       • Estimated parts needed: " + estimatedParts);
        System.out.println("       • Actual requests made: " + requests.size());
        
        if (totalBytes > THRESHOLD && requests.size() > 1) {
            double efficiency = (double) requests.size() / estimatedParts;
            System.out.println("       • Request efficiency: " + String.format("%.2f", efficiency) + 
                             " (1.0 = perfect, <1.0 = fewer requests than expected)");
        }
    }
    
    /**
     * Test different file size scenarios
     */
    private static void testDifferentFileSizeScenarios() {
        System.out.println("🧪 Testing different file size scenarios...");
        System.out.println("   Note: This test would require different test files of various sizes");
        System.out.println("   Current implementation tests with: " + OBJECT_KEY);
        
        // Test scenarios we would want to cover:
        System.out.println("\n   📋 Recommended test scenarios:");
        System.out.println("   1. Small file (< " + formatBytes(THRESHOLD) + ") → Should use single-stream");
        System.out.println("   2. Medium file (> " + formatBytes(THRESHOLD) + ", < 100MB) → Should use multipart");
        System.out.println("   3. Large file (> 100MB) → Should use multipart with multiple parts");
        System.out.println("   4. Very large file (> 1GB) → Should use multipart with part size adjustment");
        
        // For now, we'll test with the current file and different thresholds
        testWithDifferentThresholds();
    }
    
    /**
     * Test the same file with different multipart thresholds
     */
    private static void testWithDifferentThresholds() {
        System.out.println("\n   🔧 Testing with different multipart thresholds:");
        
        try {
            URL presignedUrl = generatePresignedUrl();
            
            // Test 1: Very high threshold (should force single-stream)
            System.out.println("\n   Test 1: High threshold (1GB) - should force single-stream");
            testWithThreshold(presignedUrl, 1024L * 1024 * 1024); // 1GB
            
            // Test 2: Low threshold (should force multipart)
            System.out.println("\n   Test 2: Low threshold (1MB) - should force multipart");
            testWithThreshold(presignedUrl, 1024L * 1024); // 1MB
            
            // Test 3: Default threshold
            System.out.println("\n   Test 3: Default threshold (16MB) - normal behavior");
            testWithThreshold(presignedUrl, THRESHOLD);
            
        } catch (Exception e) {
            System.out.println("   ❌ Threshold testing failed: " + e.getMessage());
        }
    }
    
    /**
     * Test download with specific threshold
     */
    private static void testWithThreshold(URL presignedUrl, long threshold) {
        List<HttpRequestInfo> httpRequests = new ArrayList<>();
        AtomicInteger requestCounter = new AtomicInteger(0);
        
        ExecutionInterceptor requestCapture = new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
                SdkHttpRequest request = context.httpRequest();
                String rangeHeader = request.firstMatchingHeader("Range").orElse("none");
                httpRequests.add(new HttpRequestInfo(
                    requestCounter.incrementAndGet(),
                    request.getUri().toString(),
                    rangeHeader,
                    "",
                    System.currentTimeMillis()
                ));
            }
        };
        
        MultipartConfiguration config = MultipartConfiguration.builder()
            .minimumPartSizeInBytes(PART_SIZE)
            .thresholdInBytes(threshold)
            .build();

        S3AsyncClient client = S3AsyncClient.builder()
            .multipartConfiguration(config)
            
            .region(Region.US_EAST_1)
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(requestCapture)
                .build())
            .build();
        
        try {
            PresignedUrlDownloadRequest request = PresignedUrlDownloadRequest.builder()
                .presignedUrl(presignedUrl)
                .build();
            
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                client.presignedUrlExtension()
                      .getObject(request, AsyncResponseTransformer.toBytes());
            
            ResponseBytes<GetObjectResponse> response = future.get();
            long bytes = response.asByteArray().length;
            
            System.out.println("       Threshold: " + formatBytes(threshold));
            System.out.println("       File size: " + formatBytes(bytes));
            System.out.println("       Requests made: " + httpRequests.size());
            
            if (bytes > threshold) {
                if (httpRequests.size() > 1) {
                    System.out.println("       ✅ File > threshold, multipart used (" + httpRequests.size() + " requests)");
                } else {
                    System.out.println("       ⚠️  File > threshold but only 1 request - multipart may have failed");
                }
            } else {
                if (httpRequests.size() == 1) {
                    System.out.println("       ✅ File < threshold, single-stream used correctly");
                } else {
                    System.out.println("       ⚠️  File < threshold but multiple requests made");
                }
            }
            
        } catch (Exception e) {
            System.out.println("       ❌ Test failed: " + e.getMessage());
        } finally {
            client.close();
        }
    }
    
    /**
     * Test error handling scenarios
     */
    private static void testErrorHandlingScenarios() {
        System.out.println("🧪 Testing error handling scenarios...");
        
        // Test 1: Invalid presigned URL
        System.out.println("\n   Test 1: Invalid presigned URL");
        testInvalidPresignedUrl();
        
        // Test 2: Expired presigned URL (simulated)
        System.out.println("\n   Test 2: Expired presigned URL handling");
        testExpiredPresignedUrlHandling();
        
        // Test 3: Network interruption simulation
        System.out.println("\n   Test 3: Network interruption handling");
        testNetworkInterruptionHandling();
    }
    
    private static void testInvalidPresignedUrl() {
        try {
            // Create an invalid URL
            URL invalidUrl = new URL("https://invalid-bucket.s3.amazonaws.com/invalid-object?invalid-signature");
            
            MultipartConfiguration config = MultipartConfiguration.builder()
                .minimumPartSizeInBytes(PART_SIZE)
                .thresholdInBytes(THRESHOLD)
                .build();

            S3AsyncClient client = S3AsyncClient.builder()
                .multipartConfiguration(config)
                
                .region(Region.US_EAST_1)
                .build();
            
            PresignedUrlDownloadRequest request = PresignedUrlDownloadRequest.builder()
                .presignedUrl(invalidUrl)
                .build();
            
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                client.presignedUrlExtension()
                      .getObject(request, AsyncResponseTransformer.toBytes());
            
            // This should fail
            future.get();
            System.out.println("       ❌ Expected failure but download succeeded");
            
        } catch (Exception e) {
            System.out.println("       ✅ Correctly handled invalid URL: " + e.getClass().getSimpleName());
            System.out.println("       Error: " + e.getMessage());
        }
    }
    
    private static void testExpiredPresignedUrlHandling() {
        System.out.println("       📝 Note: Testing expired URLs requires time-based testing");
        System.out.println("       In production, you would:");
        System.out.println("       • Generate URL with very short expiration (1 second)");
        System.out.println("       • Wait for expiration");
        System.out.println("       • Attempt download and verify proper error handling");
        System.out.println("       • Ensure graceful fallback or retry mechanisms work");
    }
    
    private static void testNetworkInterruptionHandling() {
        System.out.println("       📝 Note: Network interruption testing requires network simulation");
        System.out.println("       In production, you would:");
        System.out.println("       • Use network proxy to simulate interruptions");
        System.out.println("       • Test partial download scenarios");
        System.out.println("       • Verify retry mechanisms work correctly");
        System.out.println("       • Ensure proper cleanup of partial downloads");
    }
    
    /**
     * Performance comparison test
     */
    private static void performanceComparisonTest(URL presignedUrl) throws Exception {
        System.out.println("🧪 Performance comparison test...");
        
        // Test single-stream performance
        System.out.println("\n   📊 Measuring single-stream performance...");
        PerformanceMetrics singleStreamMetrics = measureSingleStreamPerformance(presignedUrl);
        
        // Test multipart performance
        System.out.println("\n   📊 Measuring multipart performance...");
        PerformanceMetrics multipartMetrics = measureMultipartPerformance(presignedUrl);
        
        // Compare results
        System.out.println("\n   📈 Performance Comparison Results:");
        System.out.println("   ===================================");
        System.out.println("   Single Stream:");
        System.out.println("       • Duration: " + singleStreamMetrics.durationSeconds + " seconds");
        System.out.println("       • Throughput: " + String.format("%.2f MB/s", singleStreamMetrics.throughputMbps));
        System.out.println("       • File size: " + formatBytes(singleStreamMetrics.bytes));
        
        System.out.println("\n   Multipart:");
        System.out.println("       • Duration: " + multipartMetrics.durationSeconds + " seconds");
        System.out.println("       • Throughput: " + String.format("%.2f MB/s", multipartMetrics.throughputMbps));
        System.out.println("       • File size: " + formatBytes(multipartMetrics.bytes));
        
        // Calculate improvement
        if (singleStreamMetrics.throughputMbps > 0) {
            double improvement = multipartMetrics.throughputMbps / singleStreamMetrics.throughputMbps;
            System.out.println("\n   🚀 Performance Analysis:");
            System.out.println("       • Throughput improvement: " + String.format("%.2fx", improvement));
            
            if (improvement > 1.1) {
                System.out.println("       ✅ Multipart shows significant improvement!");
            } else if (improvement > 0.9) {
                System.out.println("       ✅ Multipart performance is comparable");
            } else {
                System.out.println("       ⚠️  Multipart performance is lower - investigate");
            }
        }
    }
    
    private static PerformanceMetrics measureSingleStreamPerformance(URL presignedUrl) throws Exception {
        S3AsyncClient client = S3AsyncClient.builder()
            .region(Region.US_EAST_1)
            .build();
        
        PresignedUrlDownloadRequest request = PresignedUrlDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .build();
        
        Instant start = Instant.now();
        
        try {
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                client.presignedUrlExtension()
                      .getObject(request, AsyncResponseTransformer.toBytes());
            
            ResponseBytes<GetObjectResponse> response = future.get();
            
            Duration elapsed = Duration.between(start, Instant.now());
            long bytes = response.asByteArray().length;
            
            return new PerformanceMetrics(bytes, elapsed.getSeconds(), calculateThroughputMbps(bytes, elapsed.getSeconds()));
            
        } finally {
            client.close();
        }
    }
    
    private static PerformanceMetrics measureMultipartPerformance(URL presignedUrl) throws Exception {
        MultipartConfiguration config = MultipartConfiguration.builder()
            .minimumPartSizeInBytes(PART_SIZE)
            .thresholdInBytes(THRESHOLD)
            .build();

        S3AsyncClient client = S3AsyncClient.builder()
            .multipartConfiguration(config)
            
            .region(Region.US_EAST_1)
            .build();
        
        PresignedUrlDownloadRequest request = PresignedUrlDownloadRequest.builder()
            .presignedUrl(presignedUrl)
            .build();
        
        Instant start = Instant.now();
        
        try {
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                client.presignedUrlExtension()
                      .getObject(request, AsyncResponseTransformer.toBytes());
            
            ResponseBytes<GetObjectResponse> response = future.get();
            
            Duration elapsed = Duration.between(start, Instant.now());
            long bytes = response.asByteArray().length;
            
            return new PerformanceMetrics(bytes, elapsed.getSeconds(), calculateThroughputMbps(bytes, elapsed.getSeconds()));
            
        } finally {
            client.close();
        }
    }
    
    /**
     * Helper class for performance metrics
     */
    private static class PerformanceMetrics {
        final long bytes;
        final long durationSeconds;
        final double throughputMbps;
        
        PerformanceMetrics(long bytes, long durationSeconds, double throughputMbps) {
            this.bytes = bytes;
            this.durationSeconds = durationSeconds;
            this.throughputMbps = throughputMbps;
        }
    }
    
    /**
     * Helper class to store HTTP request information
     */
    private static class HttpRequestInfo {
        final int requestNumber;
        final String url;
        final String rangeHeader;
        final String contentRangeHeader;
        final long timestamp;
        
        HttpRequestInfo(int requestNumber, String url, String rangeHeader, String contentRangeHeader, long timestamp) {
            this.requestNumber = requestNumber;
            this.url = url;
            this.rangeHeader = rangeHeader;
            this.contentRangeHeader = contentRangeHeader;
            this.timestamp = timestamp;
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
    
    /**
     * Calculate throughput in MB/s as a double
     */
    private static double calculateThroughputMbps(long bytes, long seconds) {
        if (seconds == 0) return 0.0;
        return (bytes / (1024.0 * 1024)) / seconds;
    }
    
    /**
     * Calculate and format throughput as a string
     */
    private static String formatThroughput(long bytes, long seconds) {
        if (seconds == 0) return "N/A";
        double mbps = calculateThroughputMbps(bytes, seconds);
        return String.format("%.2f MB/s", mbps);
    }

    /**
     * NEW: Test normal object downloads to compare behavior
     */
    private static void testNormalObjectDownloads() throws Exception {
        System.out.println("🧪 BONUS TEST: Normal Object Download Behavior");
        System.out.println("==============================================");
        System.out.println("📝 Testing how normal GetObjectRequest handles multipart thresholds");
        System.out.println();

        // Test with high threshold (file should be below threshold)
        testNormalDownloadWithThreshold(1024L * 1024 * 1024, "1GB threshold (file below)");
        
        System.out.println();
        
        // Test with low threshold (file should be above threshold)
        testNormalDownloadWithThreshold(1L * 1024 * 1024, "1MB threshold (file above)");
        
        System.out.println("============================================================");
        System.out.println();
    }

    private static void testNormalDownloadWithThreshold(long threshold, String description) throws Exception {
        System.out.println("   🔬 Testing: " + description);
        
        // Create request interceptor to track HTTP requests
        NormalDownloadRequestCapture requestCapture = new NormalDownloadRequestCapture();
        
        MultipartConfiguration config = MultipartConfiguration.builder()
            .minimumPartSizeInBytes(PART_SIZE)
            .thresholdInBytes(threshold)
            .build();

        S3AsyncClient client = S3AsyncClient.builder()
            .multipartConfiguration(config)
            .region(Region.US_EAST_1)
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(requestCapture)
                .build())
            .build();

        System.out.println("   📊 Configuration: threshold=" + formatBytes(threshold) + ", partSize=" + formatBytes(PART_SIZE));
        System.out.println("   🏗️  Client type: " + client.getClass().getSimpleName());
        
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(BUCKET_NAME)
            .key(OBJECT_KEY)
            .build();

        Instant start = Instant.now();
        
        try {
            // Perform the download
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
                System.out.println("       • File size (" + formatBytes(fileSize) + ") < threshold (" + formatBytes(threshold) + ")");
                if (requests.size() == 1) {
                    System.out.println("       ✅ Single request - normal behavior for file below threshold");
                } else {
                    System.out.println("       ⚠️  Multiple requests - unexpected for file below threshold");
                }
            } else {
                System.out.println("       • File size (" + formatBytes(fileSize) + ") > threshold (" + formatBytes(threshold) + ")");
                if (requests.size() > 1) {
                    System.out.println("       ✅ Multiple requests - normal behavior for file above threshold");
                } else {
                    System.out.println("       ⚠️  Single request - unexpected for file above threshold");
                }
            }
            
        } catch (Exception e) {
            System.out.println("   ❌ Download failed: " + e.getMessage());
        } finally {
            client.close();
        }
    }

    /**
     * Request interceptor specifically for normal downloads to capture HTTP requests
     */
    private static class NormalDownloadRequestCapture implements ExecutionInterceptor {
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
}
