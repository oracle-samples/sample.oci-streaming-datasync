//Copyright (c)  2022,  Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

//This Function gets the messages from  the DataSyncStream   calls the target application’s API.
//If there is a failure in target application API call, the messages are sent to Error Streams. 
//The Error Streams to use, are configurable at the Function Application level. 

package com.example.fn;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.Response.Status.Family;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.httpgateway.HTTPGatewayContext;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails;
import com.oracle.bmc.secrets.requests.GetSecretBundleByNameRequest;
import com.oracle.bmc.secrets.responses.GetSecretBundleByNameResponse;
import com.oracle.bmc.streaming.StreamAdminClient;
import com.oracle.bmc.streaming.StreamClient;
import com.oracle.bmc.streaming.model.PutMessagesDetails;
import com.oracle.bmc.streaming.model.PutMessagesDetailsEntry;
import com.oracle.bmc.streaming.model.PutMessagesResultEntry;
import com.oracle.bmc.streaming.model.Stream;
import com.oracle.bmc.streaming.model.Stream.LifecycleState;
import com.oracle.bmc.streaming.requests.GetStreamRequest;
import com.oracle.bmc.streaming.requests.ListStreamsRequest;
import com.oracle.bmc.streaming.requests.PutMessagesRequest;
import com.oracle.bmc.streaming.responses.GetStreamResponse;
import com.oracle.bmc.streaming.responses.ListStreamsResponse;
import com.oracle.bmc.streaming.responses.PutMessagesResponse;

public class ReadDataStreamFunction {

	private static final Logger LOGGER = Logger.getLogger(ReadDataStreamFunction.class.getName());
	private final ResourcePrincipalAuthenticationDetailsProvider provider = ResourcePrincipalAuthenticationDetailsProvider
			.builder().build();
	private static final String VAULT_OCID = System.getenv().get("vault_ocid");
	private static final String UNRECOVERABLE_ERROR_STREAM_OCID = System.getenv()
			.get("unrecoverable_error_stream_ocid");
	private static final String SERVICEUNAVAILABLE_ERROR_STREAM_OCID = System.getenv()
			.get("serviceUnavailable_error_stream_ocid");
	private static final String INTERNALSERVER_ERROR_STREAM_OCID = System.getenv()
			.get("internalserver_error_stream_ocid");
	private static final String DEFAULT_ERROR_STREAM_OCID = System.getenv().get("default_error_stream_ocid");
	private static final String STREAM_COMPARTMENT_OCID = System.getenv().get("stream_compartment_ocid");
	private static final String[] OPERATIONS = new String[] { "PUT", "POST", "DELETE" };
	private static final List<String> STREAM_OCIDS = List.of(UNRECOVERABLE_ERROR_STREAM_OCID,
			SERVICEUNAVAILABLE_ERROR_STREAM_OCID, INTERNALSERVER_ERROR_STREAM_OCID, DEFAULT_ERROR_STREAM_OCID);

	/**
	 * @param incomingMessage
	 * @param httpGatewayContext
	 * @return
	 * 
	 * 
	 *         This is the entry point of the function execution.
	 * @throws InterruptedException
	 * @throws IOException
	 */
	public String handleRequest(String incomingMessage, HTTPGatewayContext httpGatewayContext) {

		ObjectMapper objectMapper = new ObjectMapper();
		StreamAdminClient streamAdminClient = StreamAdminClient.builder().build(provider);

		if (!streamExist(streamAdminClient)) {
			httpGatewayContext.setStatusCode(500);

			return "failed";

		}
		// Read the stream messages

		try {
			JsonNode jsonTree = objectMapper.readTree(incomingMessage);

			for (int i = 0; i < jsonTree.size(); i++) {
				JsonNode jsonNode = jsonTree.get(i);
				// Get the stream key and value

				String streamKey = jsonNode.get("key").asText();
				String streamMessage = jsonNode.get("value").asText();
				// Decode the stream message

				String decodedMessageValue = new String(Base64.getDecoder().decode(streamMessage.getBytes()));

				try {

					processMessage(decodedMessageValue, streamKey, streamAdminClient);

				} catch (Exception ex) {

					LOGGER.severe("Message failed with exception " + ex.getLocalizedMessage());

					populateErrorStream(streamMessage, streamKey, UNRECOVERABLE_ERROR_STREAM_OCID, streamAdminClient);
				}

			}
		} catch (JsonProcessingException e) {
			httpGatewayContext.setStatusCode(500);
			LOGGER.severe("Message processing failed with JSONProcessing exception" + e.getLocalizedMessage());
			return "failed";

		}

		return "success";

	}

	/**
	 * @param streamAdminClient
	 * @return boolean
	 * 
	 *         This method checks if a stream exist
	 */
	private boolean streamExist(StreamAdminClient streamAdminClient) {

		boolean streamsExist = true;

		for (int i = 0; i < STREAM_OCIDS.size(); i++) {

			ListStreamsRequest listRequest = ListStreamsRequest.builder().compartmentId(STREAM_COMPARTMENT_OCID)
					.id(STREAM_OCIDS.get(i)).lifecycleState(LifecycleState.Active).build();

			ListStreamsResponse listResponse = streamAdminClient.listStreams(listRequest);

			if (listResponse.getItems().isEmpty()) {

				streamsExist = false;

				LOGGER.log(Level.SEVERE,
						"Processing failed as stream OCID {0} in application configurations doesnt exist.",
						STREAM_OCIDS.get(i));

				break;
			}

		}

		return streamsExist;

	}

	/**
	 * @param streamMessage
	 * @param streamKey
	 * @param StreamAdminClient
	 * 
	 * @throws InterruptedException
	 * @throws IOException          This method parses the incoming message and
	 *                              processes it based on the targetRestApiOperation
	 *                              defined in the message
	 */
	private void processMessage(String streamMessage, String streamKey, StreamAdminClient streamAdminClient)
			throws IOException, InterruptedException {
		HttpClient httpClient = HttpClient.newHttpClient();

		String targetRestApiPayload = "";
		String vaultSecretName = "";
		String targetRestApiOperation = "";
		String targetRestApi = "";
		StringBuilder failureMessage = new StringBuilder("");
		boolean processingFailed = false;

		int responseStatusCode = 0;
		ObjectMapper objectMapper = new ObjectMapper();
		HttpRequest request = null;
		JsonNode jsonNode = null;

		jsonNode = objectMapper.readTree(streamMessage);

		// parse the incoming message

		if (jsonNode.has("vaultSecretName")) {

			vaultSecretName = jsonNode.get("vaultSecretName").asText();
		}

		if (jsonNode.get("targetRestApi") != null) {
			targetRestApi = jsonNode.get("targetRestApi").asText();
		} else {
			processingFailed = true;
			failureMessage = new StringBuilder(
					"Message could not be processed as targetRestApi node is not found in payload.");
		}
		if (jsonNode.has("targetRestApiOperation")) {

			targetRestApiOperation = jsonNode.get("targetRestApiOperation").asText();
			if (Arrays.stream(OPERATIONS).noneMatch(targetRestApiOperation::equals)) {
				processingFailed = true;
				failureMessage.append(
						" Message could not be processed as targetRestApiOperation node doesnt contain PUT,POST or DELETE.");
			}

		} else {
			processingFailed = true;
			failureMessage
					.append(" Message could not be processed as targetRestApiOperation node is not found in payload.");
		}

		if (jsonNode.get("targetRestApiPayload") != null) {
			targetRestApiPayload = jsonNode.get("targetRestApiPayload").toString();
		}
		if (processingFailed) {
			LOGGER.log(Level.SEVERE, failureMessage.toString());
			populateErrorStream(streamMessage, streamKey, UNRECOVERABLE_ERROR_STREAM_OCID, streamAdminClient);
			return;

		}
		// Get the targetRestApiHeaders section of the json payload
		JsonNode headersNode = jsonNode.get("targetRestApiHeaders");
		Map<String, String> httpHeaders = new HashMap<>();

		for (int i = 0; i < headersNode.size(); i++) {
			JsonNode headerNode = headersNode.get(i);
			httpHeaders.put(headerNode.get("key").asText(), headerNode.get("value").asText());

		}
		// process the messages based on the operation
		switch (targetRestApiOperation) {

		case "PUT": {
			Builder builder = HttpRequest.newBuilder().PUT(HttpRequest.BodyPublishers.ofString(targetRestApiPayload))
					.uri(URI.create(targetRestApi));

			request = constructHttpRequest(builder, httpHeaders, vaultSecretName);
			break;

		}

		case "POST": {

			Builder builder = HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(targetRestApiPayload))
					.uri(URI.create(targetRestApi));

			request = constructHttpRequest(builder, httpHeaders, vaultSecretName);
			break;
		}

		case "DELETE": {
			Builder builder = HttpRequest.newBuilder().DELETE().uri(URI.create(targetRestApi));

			request = constructHttpRequest(builder, httpHeaders, vaultSecretName);
			break;
		}
		default:
			LOGGER.log(Level.SEVERE, "No processing action taken");
		}

		// make the http request call

		HttpResponse<InputStream> response = httpClient.send(request, BodyHandlers.ofInputStream());
		// get the status code
		responseStatusCode = response.statusCode();

		// Populate error streams in case of a failure
		String errorStreamOCID = "";

		if ((Family.familyOf(responseStatusCode) == Family.SERVER_ERROR)
				|| (Family.familyOf(responseStatusCode) == Family.CLIENT_ERROR)) {

			switch (responseStatusCode) {

			case 503: {
				errorStreamOCID = SERVICEUNAVAILABLE_ERROR_STREAM_OCID;
				break;
			}
			case 500: {
				errorStreamOCID = INTERNALSERVER_ERROR_STREAM_OCID;
				break;
			}

			case 400: {
				errorStreamOCID = UNRECOVERABLE_ERROR_STREAM_OCID;
				break;
			}

			default:
				errorStreamOCID = DEFAULT_ERROR_STREAM_OCID;

			}

			populateErrorStream(streamMessage, streamKey, errorStreamOCID, streamAdminClient);
		}

	}

	/**
	 * @param builder
	 * @param httpHeaders
	 * @param vaultSecretName
	 * @return HttpRequest
	 * 
	 *         This method constructs http request for the target application call
	 */
	private HttpRequest constructHttpRequest(Builder builder, Map<String, String> httpHeaders, String vaultSecretName) {

		if (!vaultSecretName.equals("")) {
			String authorizationHeaderName = "Authorization";
			// Read the Vault to get the auth token
			String authToken = getSecretFromVault(vaultSecretName);
			builder.header(authorizationHeaderName, authToken);
		}
		// add targetRestApiHeaders to the request

		httpHeaders.forEach((k, v) -> builder.header(k, v));
		// add authorization token to the request

		return builder.build();

	}

	/**
	 * @param vaultSecretName
	 * @return String
	 * 
	 *         This method is used to get the auth token from the vault using
	 *         secretName
	 */
	private String getSecretFromVault(String vaultSecretName) {
		SecretsClient secretsClient = new SecretsClient(provider);

		GetSecretBundleByNameRequest getSecretBundleByNameRequest = GetSecretBundleByNameRequest.builder()

				.secretName(vaultSecretName).vaultId(VAULT_OCID).build();

		// get the secret
		GetSecretBundleByNameResponse getSecretBundleResponse = secretsClient
				.getSecretBundleByName(getSecretBundleByNameRequest);

		// get the bundle content details
		Base64SecretBundleContentDetails base64SecretBundleContentDetails = (Base64SecretBundleContentDetails) getSecretBundleResponse
				.getSecretBundle().getSecretBundleContent();
		secretsClient.close();

		return base64SecretBundleContentDetails.getContent();

	}

	/**
	 * @param streamOCID
	 * @return Stream
	 * 
	 * 
	 *         This method obtains the Stream object from the stream OCID.
	 */
	private Stream getStream(String streamOCID, StreamAdminClient streamAdminClient) {

		GetStreamResponse getResponse = streamAdminClient
				.getStream(GetStreamRequest.builder().streamId(streamOCID).build());
		return getResponse.getStream();
	}

	/**
	 * @param streamMessage
	 * @param streamKey
	 * @param errorStreamOCID
	 * @param StreamAdminClient
	 * 
	 *                          This method is used to populate the error stream
	 *                          with the failed message
	 */
	private void populateErrorStream(String streamMessage, String streamKey, String errorStreamOCID,
			StreamAdminClient streamAdminClient) {

		// Construct the stream message

		PutMessagesDetails messagesDetails = PutMessagesDetails.builder().messages(Arrays.asList(
				PutMessagesDetailsEntry.builder().key(streamKey.getBytes()).value(streamMessage.getBytes()).build()))
				.build();

		PutMessagesRequest putRequest = PutMessagesRequest.builder().streamId(errorStreamOCID)
				.putMessagesDetails(messagesDetails).build();

		// Read the response

		PutMessagesResponse putResponse = StreamClient.builder().stream(getStream(errorStreamOCID, streamAdminClient))
				.build(provider).putMessages(putRequest);
		for (PutMessagesResultEntry entry : putResponse.getPutMessagesResult().getEntries()) {
			if (entry.getError() != null) {

				LOGGER.log(Level.SEVERE, String.format("Put message error  %s, in stream with OCID %s.",
						entry.getErrorMessage(), errorStreamOCID));

			} else {

				LOGGER.log(Level.INFO,
						String.format("Message pushed to offset %s, in partition  %s in stream with OCID %s",
								entry.getOffset(), entry.getPartition(), errorStreamOCID));

			}

		}

	}

}
