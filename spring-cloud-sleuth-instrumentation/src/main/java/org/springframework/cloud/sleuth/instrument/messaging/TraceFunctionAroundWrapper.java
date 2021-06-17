/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.messaging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.function.context.catalog.FunctionAroundWrapper;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.propagation.Propagator;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * Trace representation of a {@link FunctionAroundWrapper}.
 *
 * @author Marcin Grzejszczak
 * @author Oleg Zhurakousky
 * @since 3.0.0
 */
public class TraceFunctionAroundWrapper extends FunctionAroundWrapper
		implements ApplicationListener<RefreshScopeRefreshedEvent> {

	private static final Log log = LogFactory.getLog(TraceFunctionAroundWrapper.class);

	private final Environment environment;

	private final Tracer tracer;

	private final Propagator propagator;

	private final Propagator.Setter<MessageHeaderAccessor> injector;

	private final Propagator.Getter<MessageHeaderAccessor> extractor;

	private final TraceMessageHandler traceMessageHandler;

	final Map<String, String> functionToDestinationCache = new ConcurrentHashMap<>();

	public TraceFunctionAroundWrapper(Environment environment, Tracer tracer, Propagator propagator,
			Propagator.Setter<MessageHeaderAccessor> injector, Propagator.Getter<MessageHeaderAccessor> extractor) {
		this.environment = environment;
		this.tracer = tracer;
		this.propagator = propagator;
		this.injector = injector;
		this.extractor = extractor;
		this.traceMessageHandler = TraceMessageHandler.forNonSpringIntegration(this.tracer, this.propagator,
				this.injector, this.extractor);
	}

	@Override
	protected Object doApply(Message<byte[]> message, SimpleFunctionRegistry.FunctionInvocationWrapper targetFunction) {
		MessageAndSpans invocationMessage = null;
		Span span;
		if (message == null && targetFunction.isSupplier()) { // Supplier
			span = traceMessageHandler.tracer.nextSpan().name(targetFunction.getFunctionDefinition());
		}
		else {
			if (log.isDebugEnabled()) {
				log.debug("Will retrieve the tracing headers from the message");
			}
			invocationMessage = traceMessageHandler.wrapInputMessage(message,
					inputDestination(targetFunction.getFunctionDefinition()));
			if (log.isDebugEnabled()) {
				log.debug("Wrapped input msg " + invocationMessage);
			}
			span = invocationMessage.childSpan;
		}

		Object result;
		Throwable throwable = null;
		try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
			result = invocationMessage == null ? targetFunction.get() : targetFunction.apply(invocationMessage.msg);
		}
		catch (Exception e) {
			throwable = e;
			throw e;
		}
		finally {
			traceMessageHandler.afterMessageHandled(span, throwable);
		}
		if (result == null) {
			if (log.isDebugEnabled()) {
				log.debug("Returned message is null - we have a consumer");
			}
			return null;
		}
		Message<?> msgResult = toMessage(result);

		MessageAndSpan wrappedOutputMessage;
		if (invocationMessage != null) {
			wrappedOutputMessage = traceMessageHandler.wrapOutputMessage(msgResult, invocationMessage.parentSpan,
					outputDestination(targetFunction.getFunctionDefinition()));
		}
		else {
			wrappedOutputMessage = this.getMessageAndSpans(msgResult, targetFunction.getFunctionDefinition(), span);
		}
		if (log.isDebugEnabled()) {
			log.debug("Wrapped output msg " + wrappedOutputMessage);
		}
		traceMessageHandler.afterMessageHandled(wrappedOutputMessage.span, null);
		return wrappedOutputMessage.msg;

		// if (log.isDebugEnabled()) {
		// log.debug("Will retrieve the tracing headers from the message");
		// }
		// MessageAndSpans wrappedInputMessage =
		// traceMessageHandler.wrapInputMessage(message,
		// inputDestination(targetFunction.getFunctionDefinition()));
		// if (log.isDebugEnabled()) {
		// log.debug("Wrapped input msg " + wrappedInputMessage);
		// }
		// Object result;
		// Throwable throwable = null;
		// try (Tracer.SpanInScope ws =
		// tracer.withSpan(wrappedInputMessage.childSpan.start())) {
		// result = targetFunction.apply(wrappedInputMessage.msg);
		// }
		// catch (Exception e) {
		// throwable = e;
		// throw e;
		// }
		// finally {
		// traceMessageHandler.afterMessageHandled(wrappedInputMessage.childSpan,
		// throwable);
		// }
		// if (result == null) {
		// if (log.isDebugEnabled()) {
		// log.debug("Returned message is null - we have a consumer");
		// }
		// return null;
		// }
		// Message msgResult = toMessage(result);
		// MessageAndSpan wrappedOutputMessage =
		// traceMessageHandler.wrapOutputMessage(msgResult,
		// wrappedInputMessage.parentSpan,
		// outputDestination(targetFunction.getFunctionDefinition()));
		// if (log.isDebugEnabled()) {
		// log.debug("Wrapped output msg " + wrappedOutputMessage);
		// }
		// traceMessageHandler.afterMessageHandled(wrappedOutputMessage.span, null);
		// return wrappedOutputMessage.msg;

	}

	MessageAndSpan getMessageAndSpans(Message<?> resultMessage, String name, Span spanFromMessage) {
		return traceMessageHandler.wrapOutputMessage(resultMessage, spanFromMessage, outputDestination(name));
	}

	private Message<?> toMessage(Object result) {
		if (!(result instanceof Message)) {
			return MessageBuilder.withPayload(result).build();
		}
		return (Message<?>) result;
	}

	String inputDestination(String functionDefinition) {
		return this.functionToDestinationCache.computeIfAbsent(functionDefinition, s -> {
			String bindingMappingProperty = "spring.cloud.stream.function.bindings." + s + "-in-0";
			String bindingProperty = this.environment.containsProperty(bindingMappingProperty)
					? this.environment.getProperty(bindingMappingProperty) : s + "-in-0";
			return this.environment.getProperty("spring.cloud.stream.bindings." + bindingProperty + ".destination", s);
		});
	}

	String outputDestination(String functionDefinition) {
		return this.functionToDestinationCache.computeIfAbsent(functionDefinition, s -> {
			String bindingMappingProperty = "spring.cloud.stream.function.bindings." + s + "-out-0";
			String bindingProperty = this.environment.containsProperty(bindingMappingProperty)
					? this.environment.getProperty(bindingMappingProperty) : s + "-out-0";
			return this.environment.getProperty("spring.cloud.stream.bindings." + bindingProperty + ".destination", s);
		});
	}

	@Override
	public void onApplicationEvent(RefreshScopeRefreshedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("Context refreshed, will reset the cache");
		}
		this.functionToDestinationCache.clear();
	}

}
