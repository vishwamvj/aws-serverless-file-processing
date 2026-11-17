
package com.cloud.metadata;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MetadataHandler implements
        RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Region REGION =
            Region.of(System.getenv().getOrDefault(
                    "AWS_REGION", "us-east-1"));

    private final DynamoDbClient dynamoDB;

    private static final String IMAGE_TABLE =
            System.getenv().getOrDefault(
                    "IMAGE_TABLE", "ImageProcessingMetadata");

    private static final String PDF_TABLE =
            System.getenv().getOrDefault(
                    "PDF_TABLE", "PDFProcessingMetadata");

    public MetadataHandler() {
        this(DynamoDbClient.builder()
                .region(REGION)
                .build());
    }

    MetadataHandler(DynamoDbClient dynamoDB) {
        this.dynamoDB = dynamoDB;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(
            APIGatewayV2HTTPEvent event,
            Context context) {

        if (context != null) {
            context.getLogger().log(
                    "MetadataHandler invoked in region: "
                            + REGION + "\n");
        }

        if (event == null) {
            return response(400,
                    "{\"status\":\"ERROR\",\"message\":\"Request is required\"}");
        }

        String method = event.getRequestContext() == null
                || event.getRequestContext().getHttp() == null
                ? null
                : event.getRequestContext().getHttp().getMethod();

        if ("GET".equalsIgnoreCase(method)) {
            return getMetadata(event, context);
        }

        if (!"POST".equalsIgnoreCase(method)) {
            return response(405,
                    "{\"status\":\"ERROR\",\"message\":\"Method not allowed\"}");
        }

        return putMetadata(event, context);
    }

    private APIGatewayV2HTTPResponse putMetadata(
            APIGatewayV2HTTPEvent event,
            Context context) {

        try {
            if (event.getBody() == null
                    || event.getBody().isBlank()) {
                return response(400,
                        "{\"status\":\"ERROR\",\"message\":\"Request body is required\"}");
            }

            JsonNode data = mapper.readTree(event.getBody());

            String fileType = requiredText(data, "fileType")
                    .toUpperCase();

            String fileId = requiredText(data, "fileId");

            String tableName;

            if ("IMAGE".equals(fileType)) {
                tableName = IMAGE_TABLE;
            } else if ("PDF".equals(fileType)) {
                tableName = PDF_TABLE;
            } else {
                return response(400,
                        "{\"status\":\"ERROR\",\"message\":\"Unsupported fileType\"}");
            }

            context.getLogger().log(
                    "Processing " + fileType
                            + " metadata. fileId: " + fileId
                            + ", table: " + tableName + "\n");

            Map<String, AttributeValue> item =
                    new HashMap<>();

            // Common metadata fields
            item.put("field", stringValue(fileId));
            item.put("fieldId", stringValue(fileId));
            item.put("fileId", stringValue(fileId));
            item.put("fileType", stringValue(fileType));

            putString(data, item, "fileName");
            putString(data, item, "inputBucket");
            putString(data, item, "inputKey");
            putString(data, item, "outputBucket");
            putString(data, item, "outputKey");
            putString(data, item, "status");
            putString(data, item, "processedAt");

            // Preserve additional PDF metadata
            if ("PDF".equals(fileType)) {
                putNumber(data, item, "pageCount");
                putNumber(data, item, "pdfSizeBytes");
                putNumber(data, item, "extractedTextBytes");
            }

            // Preserve additional image metadata if supplied
            if ("IMAGE".equals(fileType)) {
                putNumber(data, item, "originalSizeBytes");
                putNumber(data, item, "optimizedSizeBytes");
                putNumber(data, item, "processedSizeBytes");
                putNumber(data, item, "sizeReductionPercent");
            }

            // Store metadata in the selected DynamoDB table
            PutItemRequest request =
                    PutItemRequest.builder()
                            .tableName(tableName)
                            .item(item)
                            .build();

            dynamoDB.putItem(request);

            context.getLogger().log(
                    "Successfully stored metadata for fileId: "
                            + fileId + " in table: "
                            + tableName + "\n");

            Map<String, Object> result =
                    new HashMap<>();

            result.put("status", "SUCCESS");
            result.put("message",
                    "Metadata successfully stored");
            result.put("fileId", fileId);
            result.put("fileType", fileType);
            result.put("table", tableName);

            return response(200,
                    mapper.writeValueAsString(result));

        } catch (IllegalArgumentException e) {

            if (context != null) {
                context.getLogger().log(
                        "Invalid metadata request: "
                                + e.getMessage() + "\n");
            }

            return response(400,
                    "{\"status\":\"ERROR\",\"message\":\"Invalid metadata request\"}");

        } catch (Exception e) {

            if (context != null) {
                context.getLogger().log(
                        "Metadata storage error: "
                                + e.getMessage() + "\n");
            }

            return response(500,
                    "{\"status\":\"ERROR\",\"message\":\"Database error occurred while storing metadata\"}");
        }
    }

    private APIGatewayV2HTTPResponse getMetadata(
            APIGatewayV2HTTPEvent event,
            Context context) {

        Map<String, String> pathParameters =
                event.getPathParameters();
        String fileId = pathParameters == null
                ? null
                : pathParameters.get("fileID");
        if (fileId == null || fileId.isBlank()) {
            fileId = pathParameters == null
                    ? null
                    : pathParameters.get("fileId");
        }

        if (fileId == null || fileId.isBlank()) {
            if (context != null) {
                context.getLogger().log(
                        "Metadata GET request is missing the fileID or fileId path parameter.\n");
            }
            return response(400,
                    "{\"status\":\"ERROR\",\"message\":\"fileID or fileId path parameter is required\"}");
        }

        try {
            List<StoredMetadata> matches = new ArrayList<>();
            addMatch(matches, PDF_TABLE, "PDF", fileId, context);
            addMatch(matches, IMAGE_TABLE, "IMAGE", fileId, context);

            if (matches.isEmpty()) {
                return response(404,
                        "{\"status\":\"ERROR\",\"message\":\"Metadata not found\"}");
            }

            if (matches.size() != 1) {
                throw new IllegalStateException(
                        "Metadata fileId matched more than one table.");
            }

            StoredMetadata match = matches.get(0);
            String storedFileId = stringAttribute(
                    match.item(), "fileId");
            String storedFileType = stringAttribute(
                    match.item(), "fileType");

            if (!fileId.equals(storedFileId)
                    || !match.expectedFileType().equals(storedFileType)) {
                throw new IllegalStateException(
                        "Metadata record does not match its file identifier or table type.");
            }

            return response(200,
                    mapper.writeValueAsString(toJsonObject(match.item())));

        } catch (Exception e) {
            if (context != null) {
                StringWriter stackTrace = new StringWriter();
                e.printStackTrace(new PrintWriter(stackTrace));
                context.getLogger().log(
                        "Metadata retrieval error for fileId '"
                                + fileId + "': " + e + "\n"
                                + stackTrace);
            }

            return response(500,
                    "{\"status\":\"ERROR\",\"message\":\"Database error occurred while retrieving metadata\"}");
        }
    }

    private void addMatch(
            List<StoredMetadata> matches,
            String tableName,
            String expectedFileType,
            String fileId,
            Context context) {

        if (context != null) {
            context.getLogger().log(
                    "Retrieving metadata from table '" + tableName
                            + "' for fileId '" + fileId + "'.\n");
        }

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("fileId", stringValue(fileId)))
                .build();

        GetItemResponse result = dynamoDB.getItem(request);
        Map<String, AttributeValue> item = result.item();

        if (!item.isEmpty()) {
            matches.add(new StoredMetadata(
                    expectedFileType, item));
        }
    }

    private static String stringAttribute(
            Map<String, AttributeValue> item,
            String name) {

        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static ObjectNode toJsonObject(
            Map<String, AttributeValue> item) {

        ObjectNode result = mapper.createObjectNode();
        item.forEach((name, value) ->
                result.set(name, toJsonValue(value)));
        return result;
    }

    private static JsonNode toJsonValue(AttributeValue value) {
        if (value.s() != null) {
            return mapper.getNodeFactory().textNode(value.s());
        }
        if (value.n() != null) {
            return DecimalNode.valueOf(new BigDecimal(value.n()));
        }
        if (value.bool() != null) {
            return mapper.getNodeFactory().booleanNode(value.bool());
        }
        if (Boolean.TRUE.equals(value.nul())) {
            return NullNode.instance;
        }
        if (!value.m().isEmpty()) {
            return toJsonObject(value.m());
        }
        if (!value.l().isEmpty()) {
            ArrayNode array = mapper.createArrayNode();
            value.l().forEach(element -> array.add(toJsonValue(element)));
            return array;
        }
        if (!value.ss().isEmpty()) {
            ArrayNode array = mapper.createArrayNode();
            value.ss().forEach(array::add);
            return array;
        }
        if (!value.ns().isEmpty()) {
            ArrayNode array = mapper.createArrayNode();
            value.ns().forEach(number ->
                    array.add(DecimalNode.valueOf(new BigDecimal(number))));
            return array;
        }
        if (value.b() != null) {
            return mapper.getNodeFactory().textNode(
                    Base64.getEncoder().encodeToString(
                            value.b().asByteArray()));
        }
        if (!value.bs().isEmpty()) {
            ArrayNode array = mapper.createArrayNode();
            value.bs().forEach(binary ->
                    array.add(Base64.getEncoder().encodeToString(
                            binary.asByteArray())));
            return array;
        }

        throw new IllegalArgumentException(
                "Unsupported or empty DynamoDB attribute value.");
    }

    private record StoredMetadata(
            String expectedFileType,
            Map<String, AttributeValue> item) {
    }

    private static String requiredText(
            JsonNode data, String field) {

        JsonNode value = data.get(field);

        if (value == null || value.isNull()
                || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required field: " + field);
        }

        return value.asText();
    }

    private static AttributeValue stringValue(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static void putString(
            JsonNode data,
            Map<String, AttributeValue> item,
            String field) {

        JsonNode value = data.get(field);

        if (value != null && !value.isNull()) {
            item.put(field, stringValue(value.asText()));
        }
    }

    private static void putNumber(
            JsonNode data,
            Map<String, AttributeValue> item,
            String field) {

        JsonNode value = data.get(field);

        if (value != null && !value.isNull()
                && value.isNumber()) {
            item.put(field,
                    AttributeValue.builder()
                            .n(value.asText())
                            .build());
        }
    }

    private static APIGatewayV2HTTPResponse response(
            int statusCode, String body) {

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");

        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(body)
                .withIsBase64Encoded(false)
                .build();
    }
}
