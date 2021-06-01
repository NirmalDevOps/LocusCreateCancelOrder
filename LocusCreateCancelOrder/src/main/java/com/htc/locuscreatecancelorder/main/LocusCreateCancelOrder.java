/**
 * 
 */
package com.htc.locuscreatecancelorder.main;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.support.BasicAuthorizationInterceptor;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.htc.locuscreatecancelorder.util.LocusConstants;
import com.htc.orderhivelocusconvertorproject.locusmodel.CancelOrder;
import com.htc.orderhivelocusconvertorproject.locusmodel.Locus;
import com.htc.orderhivelocusconvertorproject.orderhivemodel.OrderHive;
import com.htc.orderhivelocusconvertorproject.serviceImpl.OrderhiveLocusConvertorServiceImpl;

/**
 * Represents a LocusCreateCancelOrder class.
 * 
 * @author HTC Global Service
 * @version 1.0
 * @since 30-03-2021
 * 
 */
public class LocusCreateCancelOrder
		implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final ObjectMapper objectMapper = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	static final Logger LOGGER = LoggerFactory.getLogger(LocusCreateCancelOrder.class);

	private long conversionStartTime;

	private long conversionEndTime;

	@Override
	public synchronized APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {

		String locusRequestBuildResponse = null;
		OrderHive orderHive = null;
		OrderhiveLocusConvertorServiceImpl orderhiveLocusConvertorServiceImplObj = new OrderhiveLocusConvertorServiceImpl();
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
		logger.log("Handling initial Request.." + input.getBody().toString());
		try {
			orderHive = objectMapper.readValue(input.getBody(), OrderHive.class);
			LOGGER.info("JSON Structure from WebHook: " + orderHive.toString());
		} catch (JsonProcessingException e) {
			LOGGER.error(LocusConstants.ERROR_MESSAGE + e);
		}

		String order_status = orderHive.getData().getOrder_status();
		if (order_status.equalsIgnoreCase("confirm") || order_status.equalsIgnoreCase("not_confirm")
				|| order_status.equalsIgnoreCase("Not Confirm") || order_status.equalsIgnoreCase("confirmed")
				|| order_status.equalsIgnoreCase("NotConfirmed") || order_status.equalsIgnoreCase("Not Confirmed")) {
			System.out.println("Inside if : " + order_status);

			// need to create
			conversionStartTime = System.currentTimeMillis();
			// CreateOrderImpl createOrderImpl = new CreateOrderImpl();
			try {
				locusRequestBuildResponse = orderhiveLocusConvertorServiceImplObj
						.buildLocusCreateOrderRequest(orderHive);
			} catch (Exception e) {
				LOGGER.error("Failed in conversion: " + e);
			}
			conversionEndTime = System.currentTimeMillis();
			System.out.println(
					"Total Time in JSON Conversion : " + (conversionStartTime - conversionEndTime) + " millisecond");

			LOGGER.info("locusRequestBuildResponse===>" + locusRequestBuildResponse);
			if (null != locusRequestBuildResponse) {
				ResponseEntity<String> locusResponse = getResponseFromLocusCreateOrderAPI(locusRequestBuildResponse);
				LOGGER.info("Got the response in main method");

				if (locusResponse.getStatusCode().value() == (HttpStatus.OK.value())) {

					if (order_status.equalsIgnoreCase("cancel")) {
						finalInvokeCancelOrder(orderHive, orderhiveLocusConvertorServiceImplObj, response,
								order_status);
						}

					LOGGER.info("locusResponse.getStatusCode().value()" + locusResponse.getStatusCode().value() + "\t"
							+ (HttpStatus.OK.value()));
					response = buildLocusCreateOrderResponse(locusResponse, LocusConstants.SUCCESS);
				} else {
					boolean successFlag = false;
					for (int count = 2; count < LocusConstants.ORDER_CREATE_COUNT; count++) {

						locusResponse = getResponseFromLocusCreateOrderAPI(locusRequestBuildResponse);
						if (locusResponse.getStatusCode().equals(HttpStatus.OK))
							successFlag = true;

						LOGGER.info("Success Flag::" + successFlag);

						if (successFlag == true) {
							
							if (order_status.equalsIgnoreCase("cancel")) {
							finalInvokeCancelOrder(orderHive, orderhiveLocusConvertorServiceImplObj, response,
									order_status);
							}

							response = buildLocusCreateOrderResponse(locusResponse, LocusConstants.SUCCESS);
							break;
						} else {
							if (count == LocusConstants.COUNT_FINAL) {
								LOGGER.info("Going to build Final Response after hitting three times:");
								response = buildLocusCreateOrderResponse(locusResponse, LocusConstants.FAILURE);
								break;
							}
						}

					}
				}
			} else {
				response.setStatusCode(HttpStatus.CONFLICT.value());
				response.setBody(LocusConstants.ERROR_IN_PROCESSING);
			}
		} else {
			response.setStatusCode(HttpStatus.CONFLICT.value());
			response.setBody("Invalid Order Status");
		}
		System.out.println("Final Response  :" + response);
		return response;
	}

	/**
	 * @param orderHive
	 * @param orderhiveLocusConvertorServiceImplObj
	 * @param response
	 * @param order_status
	 */
	private void finalInvokeCancelOrder(OrderHive orderHive,
			OrderhiveLocusConvertorServiceImpl orderhiveLocusConvertorServiceImplObj,
			APIGatewayProxyResponseEvent response, String order_status) {
		// if order-status is cancel then need to invoke cancel order api in locus
		LOGGER.info("Order created successfully and invoking the cancelOrder to cancel the order");
		try {
			APIGatewayProxyResponseEvent invokeCancelOrder = invokeCancelOrder(orderHive,
					orderhiveLocusConvertorServiceImplObj, order_status, response);
			LOGGER.info("Order created and canceled successfully based on order status : " + order_status
					+ " and status" + invokeCancelOrder.getStatusCode().toString());
		} catch (Exception e) {
			LOGGER.info("Order creation success and cancelation failed.");
		}
	}

	/**
	 * @param orderHive
	 * @param orderhiveLocusConvertorServiceImplObj
	 * @param response
	 * @param order_status
	 * @return
	 */
	private APIGatewayProxyResponseEvent invokeCancelOrder(OrderHive orderHive,
			OrderhiveLocusConvertorServiceImpl orderhiveLocusConvertorServiceImplObj, String order_status,
			APIGatewayProxyResponseEvent response) {

		System.out.println("Inside invoke cancel order");

		String locusRequestBuildResponse = null;

		// Logic for find out order status
		if (order_status.equalsIgnoreCase("cancel")) {
			// CancelOrderImpl cancelOrderImpl = new CancelOrderImpl();

			String orderId = String.valueOf(orderHive.getData().getId());
			try {
				locusRequestBuildResponse = orderhiveLocusConvertorServiceImplObj
						.buildLocusCancelOrderRequest(orderHive);
			} catch (Exception e) {
				LOGGER.error("Conversion error " + locusRequestBuildResponse);
			}

			if (null != locusRequestBuildResponse) {
				ResponseEntity<String> locusResponse = getResponseFromLocusCancelOrderAPI(locusRequestBuildResponse);
				LOGGER.info("Got the response in main method" + locusResponse);

				if (locusResponse.getStatusCode().value() == (HttpStatus.OK.value())) {
					response = buildLocusCancelOrderResponse(locusResponse, LocusConstants.SUCCESS, orderId);
				} else {
					boolean successFlag = false;
					for (int count = 2; count < LocusConstants.ORDER_CANCEL_COUNT; count++) {

						LOGGER.info("Inside Else:: Count::" + count);
						locusResponse = getResponseFromLocusCancelOrderAPI(locusRequestBuildResponse);
						if (locusResponse.getStatusCode().equals(HttpStatus.OK))
							successFlag = true;
						if (successFlag == true) {
							response = buildLocusCancelOrderResponse(locusResponse, LocusConstants.SUCCESS, orderId);
							break;
						} else {
							if (count == LocusConstants.COUNT_FINAL) {
								LOGGER.info("Going to build Final Response after hitting three times:");
								response = buildLocusCancelOrderResponse(locusResponse, LocusConstants.FAILURE,
										orderId);
								break;
							}
						}
					}
				}
			} else {
				response.setStatusCode(HttpStatus.CONFLICT.value());
				response.setBody(LocusConstants.ERROR_IN_PROCESSING);
			}
		}

		return response;
	}

	private APIGatewayProxyResponseEvent buildLocusCreateOrderResponse(ResponseEntity<String> locusResponse,
			String status) {
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
		JSONObject locusAPIResponseJsonObj = null;

		if (status.equalsIgnoreCase(LocusConstants.SUCCESS)) {
			response.setStatusCode(HttpStatus.OK.value());
			// response.setBody(LocusConstants.ORDER_EDIT_SUCCESS_MESSAGE);

			LOGGER.info(LocusConstants.SUCCESS_STATUS_CODE + HttpStatus.OK.value());
			// LOGGER.info(LocusConstants.ORDER_EDIT_SUCCESS_MESSAGE);

		} else {
			response.setStatusCode(locusResponse.getStatusCodeValue());
			// response.setBody(LocusConstants.ORDER_EDIT_FAILED_MESSAGE);
			LOGGER.info(LocusConstants.ERROR_STATUS_CODE + locusResponse.getStatusCodeValue());
			// LOGGER.info(LocusConstants.ORDER_EDIT_FAILED_MESSAGE);
		}

		locusAPIResponseJsonObj = new JSONObject(locusResponse.getBody());
		response.setBody(locusAPIResponseJsonObj.toString());
		return response;
	}

	private APIGatewayProxyResponseEvent buildLocusCancelOrderResponse(ResponseEntity<String> locusResponse,
			String status, String orderId) {
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
		if (status.equalsIgnoreCase(LocusConstants.SUCCESS)) {
			response.setStatusCode(HttpStatus.OK.value());
			response.setBody(LocusConstants.ORDER_ID + orderId + LocusConstants.CANCEL_SUCCESS_MESSAGE);
			LOGGER.info(LocusConstants.SUCCESS_CODE + HttpStatus.OK.value());
			LOGGER.info(LocusConstants.ORDER_ID + orderId + LocusConstants.CANCEL_SUCCESS_MESSAGE);
		} else {
			response.setStatusCode(locusResponse.getStatusCodeValue());
			response.setBody(LocusConstants.ORDER_ID + orderId + LocusConstants.CANCEL_FAILED_MESSAGE);
			LOGGER.info(LocusConstants.ERROR_STATUS_CODE + locusResponse.getStatusCodeValue());
			LOGGER.info(LocusConstants.ORDER_ID + orderId + LocusConstants.CANCEL_FAILED_MESSAGE);
		}
		return response;
	}

	private ResponseEntity<String> getResponseFromLocusCancelOrderAPI(String locusRequestJson) {
		ResponseEntity<String> locusResponse = null;

		CancelOrder locusRequestModelObj = null;
		try {
			locusRequestModelObj = objectMapper.readValue(locusRequestJson, CancelOrder.class);
			System.out.println("locusRequestModelObj =====>" + locusRequestModelObj);
		} catch (JsonProcessingException e) {
			LOGGER.error("Error : " + e);
		}
		String requestJson = "";
		try {
			requestJson = objectMapper.writeValueAsString(locusRequestModelObj);
		} catch (JsonProcessingException e) {
			LOGGER.error("Error : " + e);
		}

		try {
			StringBuilder locusUrlBuilder = buildCancelOrderURL();

			String finalLocusURL = locusUrlBuilder.toString();

			LOGGER.info("finalLocusURL====>" + finalLocusURL);
			HttpHeaders headers = setHeaderContent();
			RestTemplate restTemplate = new RestTemplate(getClientHttpRequestFactory());
			HttpEntity<String> entity = new HttpEntity<String>(requestJson.toString(), headers);
			LOGGER.info("HttpEntity Body As aString::" + entity.getBody().toString());
			LOGGER.info("Going to call Locus API::" + locusUrlBuilder);
			restTemplate.getInterceptors().add(
					new BasicAuthorizationInterceptor(LocusConstants.CLIENT_ID, LocusConstants.CLIENT_AUTHENTICATION));

			// send request and parse result
			locusResponse = restTemplate.exchange(finalLocusURL, HttpMethod.POST, entity, String.class);
			LOGGER.info("Locus response after invoing method" + locusResponse);
		} catch (HttpClientErrorException e) {
			locusResponse = new ResponseEntity<String>(e.getStatusCode());

			LOGGER.error("Error occured : " + e.getMessage());
		}
		return locusResponse;
	}

	/**
	 * @return
	 */
	private StringBuilder buildCancelOrderURL() {
		StringBuilder locusUrlBuilder = new StringBuilder("https://oms.locus-api.com/v1/client/")
				.append(LocusConstants.CLIENT_ID).append("/order-status-update");
		return locusUrlBuilder;
	}

	private ResponseEntity<String> getResponseFromLocusCreateOrderAPI(String locusRequestJson) {
		ResponseEntity<String> locusResponse = null;

		Locus locusEditModelObjectBody = null;
		try {
			locusEditModelObjectBody = objectMapper.readValue(locusRequestJson, Locus.class);
			LOGGER.info("locusEditModelObjectBody :" + locusEditModelObjectBody);
			System.out.println("locusEditModelObjectBody :" + locusEditModelObjectBody);
		} catch (JsonProcessingException e) {
			LOGGER.error(LocusConstants.ERROR_MESSAGE + e.getMessage());
		}

		String requestJson = "";
		try {
			requestJson = objectMapper.writeValueAsString(locusEditModelObjectBody);
			System.out.println("requestJson :" + requestJson);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}

		LOGGER.info("Order id:" + locusEditModelObjectBody.getId() + "\t Client Id: "
				+ locusEditModelObjectBody.getClientId());

		try {
			StringBuilder locusUrlBuilder = buildCreateOrderURL(locusEditModelObjectBody);

			String locusURL = locusUrlBuilder.toString();
			HttpHeaders headers = setHeaderContent();

			RestTemplate restTemplate = new RestTemplate(getClientHttpRequestFactory());

			// LOGGER.info("Rest Template Obj initialized");

			HttpEntity<String> entity = new HttpEntity<String>(requestJson.toString(), headers);

			// LOGGER.info("HttpEntity Body As aString::" + entity.getBody().toString());

			LOGGER.info("Going to call Locus API::" + locusURL);

			restTemplate.getInterceptors().add(
					new BasicAuthorizationInterceptor(LocusConstants.CLIENT_ID, LocusConstants.CLIENT_AUTHENTICATION));

			// send request and parse result
			locusResponse = restTemplate.exchange(locusURL, HttpMethod.PUT, entity, String.class);
		} catch (HttpClientErrorException e) {
			locusResponse = new ResponseEntity<String>(e.getStatusCode());

			LOGGER.error("Error occured : " + e.getMessage());
		}
		return locusResponse;
	}

	/**
	 * @param locusEditModelObjectBody
	 * @return
	 */
	private StringBuilder buildCreateOrderURL(Locus locusEditModelObjectBody) {
		StringBuilder locusUrlBuilder = new StringBuilder("https://oms.locus-api.com/v1/client/")
				.append(locusEditModelObjectBody.getClientId()).append("/order/")
				.append(locusEditModelObjectBody.getId()).append("?overwrite=true");
		return locusUrlBuilder;
	}

	private SimpleClientHttpRequestFactory getClientHttpRequestFactory() {
		SimpleClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory();
		// Connect timeout
		clientHttpRequestFactory.setConnectTimeout(LocusConstants.CONNECTION_TIME_OUT);

		// Read timeout
		clientHttpRequestFactory.setReadTimeout(LocusConstants.READING_TIME_OUT);
		return clientHttpRequestFactory;
	}

	private HttpHeaders setHeaderContent() {
		HttpHeaders headers = new HttpHeaders();
		// Base64.Encoder encoder = Base64.getEncoder();

		// String clientIdAndSecret = "AI8VB2XP22X8ZVNWTOWYRZ2BNUDIWF24" + ":" +
		// "46YE18NHS8NKX8XWRCYELN4KVCALC8EA";
		// String clientIdAndSecretBase64 =
		// encoder.encodeToString(clientIdAndSecret.getBytes());

		// System.out.println("Base64 converted value::"+clientIdAndSecretBase64);

		// headers.add("Authorization", "Basic " + clientIdAndSecretBase64);
		headers.setContentType(MediaType.APPLICATION_JSON);

		return headers;
	}

}
