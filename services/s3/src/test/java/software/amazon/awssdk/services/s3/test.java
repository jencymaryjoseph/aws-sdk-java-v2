package software.amazon.awssdk.services.s3;


import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presignedurl.AsyncPresignedUrlExtension;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;


public class test {
    public static void main(String[] args) throws MalformedURLException {
        // 1. generate pre signed URL
        S3Presigner presigner = S3Presigner.builder()
                .build();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket("jency-test-bucket")
                .key("Amazon Q.dmg")
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(java.time.Duration.ofDays(5))
                .getObjectRequest(getObjectRequest)
                .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        URL presignedUrl = presignedRequest.url();

        // 2. client with multipart enabled
        S3AsyncClient s3AsyncClient =
                S3AsyncClient.builder().multipartEnabled(true).overrideConfiguration(f ->f.addExecutionInterceptor(new HeaderLoggingInterceptor())).build();




        // 3.1 normal object
//        byte[] byteArray = s3AsyncClient.getObject(r -> r.bucket("jency-test-bucket").key("Amazon Q.dmg").partNumber(1),
//                                                   AsyncResponseTransformer.toBytes()).join().asByteArray();
//
//        System.out.println("byteArray " + byteArray.length);

        // 3.1 presigned url object
        AsyncPresignedUrlExtension asyncPresignedUrlExtension = s3AsyncClient.presignedUrlExtension();


        Instant now = Instant.now();

        // 4.1 normal object
//        byte[] byteArray = s3AsyncClient.getObject(g ->g.bucket("jency-test-bucket").key("Amazon Q.dmg"),
//                                                  AsyncResponseTransformer.toBytes()).join().asByteArray();

        // 4.2 presigned url
        byte[] byteArray = asyncPresignedUrlExtension.getObject(r -> r.presignedUrl(presignedUrl),
                AsyncResponseTransformer.toBytes()).join().asByteArray();

        Instant then = Instant.now();

        System.out.println("byteArray  " +byteArray.length + " total time  " + (then.toEpochMilli() - now.toEpochMilli()));

    }

    public static class HeaderLoggingInterceptor implements ExecutionInterceptor {
        private  final Logger LOG = LoggerFactory.getLogger(HeaderLoggingInterceptor.class);

        @Override
        public void afterTransmission(Context.AfterTransmission context, ExecutionAttributes executionAttributes) {
            SdkHttpResponse response = context.httpResponse();
            LOG.info("=== RESPONSE HEADERS ===");
            response.headers().forEach((key, values) ->
                    LOG.info("{}: {}", key, String.join(", ", values))
            );
            LOG.info("======================");
        }

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
            SdkHttpRequest request = context.httpRequest();
            LOG.info("=== REQUEST HEADERS ===");
            request.headers().forEach((key, values) ->
                    LOG.info("{}: {}", key, String.join(", ", values))
            );
            LOG.info("Request Method: {}", request.method());
            LOG.info("Request Path: {}", request.encodedPath());
            LOG.info("Request Query Parameters: {}", request.rawQueryParameters());
            LOG.info("======================");
        }

    }

}