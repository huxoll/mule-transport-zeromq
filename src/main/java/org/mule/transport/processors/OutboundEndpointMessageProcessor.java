package org.mule.transport.processors;

import org.mule.api.*;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.construct.FlowConstructAware;
import org.mule.api.context.MuleContextAware;
import org.mule.api.expression.ExpressionManager;
import org.mule.api.lifecycle.*;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.registry.RegistrationException;
import org.mule.api.transformer.DataType;
import org.mule.api.transformer.Transformer;
import org.mule.api.transformer.TransformerException;
import org.mule.config.i18n.CoreMessages;
import org.mule.config.i18n.MessageFactory;
import org.mule.transformer.TransformerTemplate;
import org.mule.transformer.types.DataTypeFactory;
import org.mule.transport.NullPayload;
import org.mule.transport.ZeroMQTransport;
import org.mule.transport.adapters.ZeroMQTransportConnectionManager;
import org.mule.transport.adapters.ZeroMQTransportLifecycleAdapter;
import org.mule.util.TemplateParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * OutboundEndpointMessageProcessor invokes the {@link org.mule.transport.ZeroMQTransport#outboundEndpoint(byte[])} method in {@link org.mule.transport.ZeroMQTransport }. For each argument there is a field in this processor to match it.  Before invoking the actual method the processor will evaluate and transform where possible to the expected argument type.
 */
public class OutboundEndpointMessageProcessor
        implements FlowConstructAware, MuleContextAware, Disposable, Initialisable, Startable, Stoppable, MessageProcessor, Runnable {

    private Object payload;
    private byte[] _payloadType;
    private Object exchangePattern;
    private ZeroMQTransport.ExchangePattern _exchangePatternType;
    private Object socketOperation;
    private ZeroMQTransport.SocketOperation _socketOperationType;
    private Object address;
    private String _addressType;
    private Object filter;
    private String _filterType;
    private static Logger logger = LoggerFactory.getLogger(OutboundEndpointMessageProcessor.class);
    /**
     * Module object
     */
    private Object moduleObject;
    /**
     * Mule Context
     */
    private MuleContext muleContext;
    /**
     * Mule Expression Manager
     */
    private ExpressionManager expressionManager;
    /**
     * Mule Pattern Info
     */
    private TemplateParser.PatternInfo patternInfo;
    /**
     * Flow construct
     */
    private FlowConstruct flowConstruct;
    /**
     * Variable used to track how many retries we have attempted on this message processor
     */
    private AtomicInteger retryCount;
    /**
     * Maximum number of retries that can be attempted.
     */
    private int retryMax;

    /**
     * Obtains the expression manager from the Mule context and initialises the connector. If a target object  has not been set already it will search the Mule registry for a default one.
     *
     * @throws org.mule.api.lifecycle.InitialisationException
     *
     */
    public void initialise()
            throws InitialisationException {
        retryCount = new AtomicInteger();
        expressionManager = muleContext.getExpressionManager();
        patternInfo = TemplateParser.createMuleStyleParser().getStyle();
        if (moduleObject == null) {
            try {
                moduleObject = muleContext.getRegistry().lookupObject(ZeroMQTransportConnectionManager.class);
                if (moduleObject == null) {
                    moduleObject = new ZeroMQTransportConnectionManager();
                    muleContext.getRegistry().registerObject(ZeroMQTransportConnectionManager.class.getName(), moduleObject);
                }
            } catch (RegistrationException e) {
                throw new InitialisationException(CoreMessages.initialisationFailure("org.mule.transport.adapters.ZeroMQTransportConnectionManager"), e, this);
            }
        }
        if (moduleObject instanceof String) {
            moduleObject = muleContext.getRegistry().lookupObject(((String) moduleObject));
            if (moduleObject == null) {
                throw new InitialisationException(MessageFactory.createStaticMessage("Cannot find object by config name"), this);
            }
        }

        Thread thread = new Thread(this, "Connection Thread");
        thread.start();
    }

    public void start()
            throws MuleException {
    }

    public void stop()
            throws MuleException {
    }

    public void dispose() {
    }

    /**
     * Set the Mule context
     *
     * @param context Mule context to set
     */
    public void setMuleContext(MuleContext context) {
        this.muleContext = context;
    }

    /**
     * Sets flow construct
     *
     * @param flowConstruct Flow construct to set
     */
    public void setFlowConstruct(FlowConstruct flowConstruct) {
        this.flowConstruct = flowConstruct;
    }

    /**
     * Sets the instance of the object under which the processor will execute
     *
     * @param moduleObject Instace of the module
     */
    public void setModuleObject(Object moduleObject) {
        this.moduleObject = moduleObject;
    }

    /**
     * Sets retryMax
     *
     * @param value Value to set
     */
    public void setRetryMax(int value) {
        this.retryMax = value;
    }

    /**
     * Sets payload
     *
     * @param value Value to set
     */
    public void setPayload(Object value) {
        this.payload = value;
    }

    /**
     * Sets socketOperation
     *
     * @param value Value to set
     */
    public void setSocketOperation(Object value) {
        this.socketOperation = value;
    }

    /**
     * Sets address
     *
     * @param value Value to set
     */
    public void setAddress(Object value) {
        this.address = value;
    }

    /**
     * Sets exchangePattern
     *
     * @param value Value to set
     */
    public void setExchangePattern(Object value) {
        this.exchangePattern = value;
    }

    /**
     * Sets filter
     *
     * @param value Value to set
     */
    public void setFilter(Object value) {
        this.filter = value;
    }

    /**
     * Get all superclasses and interfaces recursively.
     *
     * @param classes List of classes to which to add all found super classes and interfaces.
     * @param clazz   The class to start the search with.
     */
    private void computeClassHierarchy(Class clazz, List classes) {
        for (Class current = clazz; (current != null); current = current.getSuperclass()) {
            if (classes.contains(current)) {
                return;
            }
            classes.add(current);
            for (Class currentInterface : current.getInterfaces()) {
                computeClassHierarchy(currentInterface, classes);
            }
        }
    }

    /**
     * Checks whether the specified class parameter is an instance of {@link java.util.List }
     *
     * @param clazz <code>Class</code> to check.
     * @return
     */
    private boolean isListClass(Class clazz) {
        List<Class> classes = new ArrayList<Class>();
        computeClassHierarchy(clazz, classes);
        return classes.contains(List.class);
    }

    /**
     * Checks whether the specified class parameter is an instance of {@link java.util.Map }
     *
     * @param clazz <code>Class</code> to check.
     * @return
     */
    private boolean isMapClass(Class clazz) {
        List<Class> classes = new ArrayList<Class>();
        computeClassHierarchy(clazz, classes);
        return classes.contains(Map.class);
    }

    private boolean isList(Type type) {
        if ((type instanceof Class) && isListClass(((Class) type))) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            return isList(((ParameterizedType) type).getRawType());
        }
        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            return ((upperBounds.length != 0) && isList(upperBounds[0]));
        }
        return false;
    }

    private boolean isMap(Type type) {
        if ((type instanceof Class) && isMapClass(((Class) type))) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            return isMap(((ParameterizedType) type).getRawType());
        }
        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            return ((upperBounds.length != 0) && isMap(upperBounds[0]));
        }
        return false;
    }

    private boolean isAssignableFrom(Type expectedType, Class clazz) {
        if (expectedType instanceof Class) {
            if (((Class) expectedType).isPrimitive()) {
                if (((Class) expectedType).getName().equals("boolean") && (clazz == Boolean.class)) {
                    return true;
                }
                if (((Class) expectedType).getName().equals("byte") && (clazz == Byte.class)) {
                    return true;
                }
                if (((Class) expectedType).getName().equals("short") && (clazz == Short.class)) {
                    return true;
                }
                if (((Class) expectedType).getName().equals("char") && (clazz == Character.class)) {
                    return true;
                }
                if (((Class) expectedType).getName().equals("int") && (clazz == Integer.class)) {
                    return true;
                }
                if (((Class) expectedType).getName().equals("float") && (clazz == Float.class)) {
                    return true;
                }
                if (((Class) expectedType).getName().equals("long") && (clazz == Long.class)) {
                    return true;
                }
                if (((Class) expectedType).getName().equals("double") && (clazz == Double.class)) {
                    return true;
                }
                return false;
            } else {
                return ((Class) expectedType).isAssignableFrom(clazz);
            }
        }
        if (expectedType instanceof ParameterizedType) {
            return isAssignableFrom(((ParameterizedType) expectedType).getRawType(), clazz);
        }
        if (expectedType instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) expectedType).getUpperBounds();
            if (upperBounds.length != 0) {
                return isAssignableFrom(upperBounds[0], clazz);
            }
        }
        return false;
    }

    private Object evaluate(MuleMessage muleMessage, Object source) {
        if (source instanceof String) {
            String stringSource = ((String) source);
            if (stringSource.startsWith(patternInfo.getPrefix()) && stringSource.endsWith(patternInfo.getSuffix())) {
                return expressionManager.evaluate(stringSource, muleMessage);
            } else {
                return expressionManager.parse(stringSource, muleMessage);
            }
        }
        return source;
    }

    private Object evaluateAndTransform(MuleMessage muleMessage, Type expectedType, String expectedMimeType, Object source)
            throws TransformerException {
        if (source == null) {
            return source;
        }
        Object target = null;
        if (isList(source.getClass())) {
            if (isList(expectedType)) {
                List newList = new ArrayList();
                Type valueType = ((ParameterizedType) expectedType).getActualTypeArguments()[0];
                ListIterator iterator = ((List) source).listIterator();
                while (iterator.hasNext()) {
                    Object subTarget = iterator.next();
                    newList.add(evaluateAndTransform(muleMessage, valueType, expectedMimeType, subTarget));
                }
                target = newList;
            } else {
                target = source;
            }
        } else {
            if (isMap(source.getClass())) {
                if (isMap(expectedType)) {
                    Type keyType = Object.class;
                    Type valueType = Object.class;
                    if (expectedType instanceof ParameterizedType) {
                        keyType = ((ParameterizedType) expectedType).getActualTypeArguments()[0];
                        valueType = ((ParameterizedType) expectedType).getActualTypeArguments()[1];
                    }
                    Map map = ((Map) source);
                    Map newMap = new HashMap();
                    for (Object entryObj : map.entrySet()) {
                        {
                            Map.Entry entry = ((Map.Entry) entryObj);
                            Object newKey = evaluateAndTransform(muleMessage, keyType, expectedMimeType, entry.getKey());
                            Object newValue = evaluateAndTransform(muleMessage, valueType, expectedMimeType, entry.getValue());
                            newMap.put(newKey, newValue);
                        }
                    }
                    target = newMap;
                } else {
                    target = source;
                }
            } else {
                target = evaluate(muleMessage, source);
            }
        }
        if ((target != null) && (!isAssignableFrom(expectedType, target.getClass()))) {
            DataType sourceDataType = DataTypeFactory.create(target.getClass());
            DataType targetDataType = null;
            if (expectedMimeType != null) {
                targetDataType = DataTypeFactory.create(((Class) expectedType), expectedMimeType);
            } else {
                targetDataType = DataTypeFactory.create(((Class) expectedType));
            }
            Transformer t = muleContext.getRegistry().lookupTransformer(sourceDataType, targetDataType);
            return t.transform(target);
        } else {
            return target;
        }
    }

    /**
     * Invokes the MessageProcessor.
     *
     * @param event MuleEvent to be processed
     * @throws org.mule.api.MuleException
     */
    public MuleEvent process(MuleEvent event)
            throws MuleException {
        MuleMessage _muleMessage = event.getMessage();
        ZeroMQTransportConnectionManager _castedModuleObject = null;
        if (moduleObject instanceof String) {
            _castedModuleObject = ((ZeroMQTransportConnectionManager) muleContext.getRegistry().lookupObject(((String) moduleObject)));
            if (_castedModuleObject == null) {
                throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), event, new RuntimeException("Cannot find the configuration specified by the config-ref attribute."));
            }
        } else {
            _castedModuleObject = ((ZeroMQTransportConnectionManager) moduleObject);
        }
        ZeroMQTransport.ExchangePattern _transformedExchangePattern = null;
        ZeroMQTransport.SocketOperation _transformedSocketOperation = null;
        String _transformedAddress = null;
        String _transformedFilter = null;
        ZeroMQTransportLifecycleAdapter connection = null;
        try {
            if (exchangePattern != null) {
                _transformedExchangePattern = ((ZeroMQTransport.ExchangePattern) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_exchangePatternType").getGenericType(), null, exchangePattern));
            } else {
                if (_castedModuleObject.getExchangePattern() == null) {
                    throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), event, new RuntimeException("You must provide a exchangePattern at the config or the message processor level."));
                }
                _transformedExchangePattern = ((ZeroMQTransport.ExchangePattern) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_exchangePatternType").getGenericType(), null, _castedModuleObject.getExchangePattern()));
            }
            if (socketOperation != null) {
                _transformedSocketOperation = ((ZeroMQTransport.SocketOperation) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_socketOperationType").getGenericType(), null, socketOperation));
            } else {
                if (_castedModuleObject.getSocketOperation() == null) {
                    throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), event, new RuntimeException("You must provide a socketOperation at the config or the message processor level."));
                }
                _transformedSocketOperation = ((ZeroMQTransport.SocketOperation) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_socketOperationType").getGenericType(), null, _castedModuleObject.getSocketOperation()));
            }
            if (address != null) {
                _transformedAddress = ((String) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_addressType").getGenericType(), null, address));
            } else {
                if (_castedModuleObject.getAddress() == null) {
                    throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), event, new RuntimeException("You must provide a address at the config or the message processor level."));
                }
                _transformedAddress = ((String) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_addressType").getGenericType(), null, _castedModuleObject.getAddress()));
            }
            if (filter != null) {
                _transformedFilter = ((String) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_filterType").getGenericType(), null, filter));
            } else {
                if (_castedModuleObject.getFilter() != null) {
                    _transformedFilter = ((String) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_filterType").getGenericType(), null, _castedModuleObject.getFilter()));
                }

            }
            byte[] _transformedPayload = ((byte[]) evaluateAndTransform(_muleMessage, OutboundEndpointMessageProcessor.class.getDeclaredField("_payloadType").getGenericType(), null, "#[payload]"));
            if (logger.isDebugEnabled()) {
                StringBuilder _messageStringBuilder = new StringBuilder();
                _messageStringBuilder.append("Attempting to acquire a connection using ");
                _messageStringBuilder.append("[socketOperation = ");
                _messageStringBuilder.append(_transformedSocketOperation);
                _messageStringBuilder.append("] ");
                _messageStringBuilder.append("[address = ");
                _messageStringBuilder.append(_transformedAddress);
                _messageStringBuilder.append("] ");
                _messageStringBuilder.append("[exchangePattern = ");
                _messageStringBuilder.append(_transformedExchangePattern);
                _messageStringBuilder.append("] ");
                _messageStringBuilder.append("[filter = ");
                _messageStringBuilder.append(_transformedFilter);
                _messageStringBuilder.append("] ");
                logger.debug(_messageStringBuilder.toString());
            }
            connection = _castedModuleObject.acquireConnection(new ZeroMQTransportConnectionManager.ConnectionKey(_transformedExchangePattern, _transformedSocketOperation, _transformedAddress, _transformedFilter, false));
            if (connection == null) {
                throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), event, new RuntimeException("Cannot create connection"));
            } else {
                if (logger.isDebugEnabled()) {
                    StringBuilder _messageStringBuilder = new StringBuilder();
                    _messageStringBuilder.append("Connection has been acquired with ");
                    _messageStringBuilder.append("[id = ");
                    _messageStringBuilder.append(connection.connectionId());
                    _messageStringBuilder.append("] ");
                    logger.debug(_messageStringBuilder.toString());
                }
            }
            retryCount.getAndIncrement();
            Object resultPayload;
            resultPayload = connection.outboundEndpoint(_transformedPayload);
            TransformerTemplate.OverwitePayloadCallback overwritePayloadCallback = null;
            if (resultPayload == null) {
                overwritePayloadCallback = new TransformerTemplate.OverwitePayloadCallback(NullPayload.getInstance());
            } else {
                overwritePayloadCallback = new TransformerTemplate.OverwitePayloadCallback(resultPayload);
            }
            List<Transformer> transformerList;
            transformerList = new ArrayList<Transformer>();
            transformerList.add(new TransformerTemplate(overwritePayloadCallback));
            event.getMessage().applyTransformers(event, transformerList);
            retryCount.set(0);
            return event;
        } catch (Exception e) {
            throw new MessagingException(CoreMessages.failedToInvoke("outboundEndpoint"), event, e);
        } finally {
            try {
                if (connection != null) {
                    if (logger.isDebugEnabled()) {
                        StringBuilder _messageStringBuilder = new StringBuilder();
                        _messageStringBuilder.append("Releasing the connection back into the pool [id=");
                        _messageStringBuilder.append(connection.connectionId());
                        _messageStringBuilder.append("].");
                        logger.debug(_messageStringBuilder.toString());
                    }
                    _castedModuleObject.releaseConnection(new ZeroMQTransportConnectionManager.ConnectionKey(_transformedExchangePattern, _transformedSocketOperation, _transformedAddress, _transformedFilter, false), connection);
                }
            } catch (Exception e) {
                throw new MessagingException(CoreMessages.failedToInvoke("outboundEndpoint"), event, e);
            }
        }
    }

    @Override
    public void run() {
        try {
            ZeroMQTransportConnectionManager _castedModuleObject = null;
            if (moduleObject instanceof String) {
                _castedModuleObject = ((ZeroMQTransportConnectionManager) muleContext.getRegistry().lookupObject(((String) moduleObject)));
                if (_castedModuleObject == null) {
                    throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), (MuleEvent) null, new RuntimeException("Cannot find the configuration specified by the config-ref attribute."));
                }
            } else {
                _castedModuleObject = ((ZeroMQTransportConnectionManager) moduleObject);
            }
            ZeroMQTransport.ExchangePattern _transformedExchangePattern = null;
            ZeroMQTransport.SocketOperation _transformedSocketOperation = null;
            String _transformedAddress = null;
            String _transformedFilter = null;
            ZeroMQTransportLifecycleAdapter connection = null;
            try {
                if (exchangePattern != null) {
                    _transformedExchangePattern = ((ZeroMQTransport.ExchangePattern) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_exchangePatternType").getGenericType(), null, exchangePattern));
                } else {
                    if (_castedModuleObject.getExchangePattern() == null) {
                        throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), (MuleEvent) null, new RuntimeException("You must provide a exchangePattern at the config or the message processor level."));
                    }
                    _transformedExchangePattern = ((ZeroMQTransport.ExchangePattern) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_exchangePatternType").getGenericType(), null, _castedModuleObject.getExchangePattern()));
                }
                if (socketOperation != null) {
                    _transformedSocketOperation = ((ZeroMQTransport.SocketOperation) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_socketOperationType").getGenericType(), null, socketOperation));
                } else {
                    if (_castedModuleObject.getSocketOperation() == null) {
                        throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), (MuleEvent) null, new RuntimeException("You must provide a socketOperation at the config or the message processor level."));
                    }
                    _transformedSocketOperation = ((ZeroMQTransport.SocketOperation) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_socketOperationType").getGenericType(), null, _castedModuleObject.getSocketOperation()));
                }
                if (address != null) {
                    _transformedAddress = ((String) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_addressType").getGenericType(), null, address));
                } else {
                    if (_castedModuleObject.getAddress() == null) {
                        throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), (MuleEvent) null, new RuntimeException("You must provide a address at the config or the message processor level."));
                    }
                    _transformedAddress = ((String) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_addressType").getGenericType(), null, _castedModuleObject.getAddress()));
                }
                if (filter != null) {
                    _transformedFilter = ((String) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_filterType").getGenericType(), null, filter));
                } else {
                    if (_castedModuleObject.getFilter() != null) {
                        _transformedFilter = ((String) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_filterType").getGenericType(), null, _castedModuleObject.getFilter()));
                    }

                }
                byte[] _transformedPayload = ((byte[]) evaluateAndTransform(null, OutboundEndpointMessageProcessor.class.getDeclaredField("_payloadType").getGenericType(), null, "#[payload]"));
                if (logger.isDebugEnabled()) {
                    StringBuilder _messageStringBuilder = new StringBuilder();
                    _messageStringBuilder.append("Attempting to acquire a connection using ");
                    _messageStringBuilder.append("[socketOperation = ");
                    _messageStringBuilder.append(_transformedSocketOperation);
                    _messageStringBuilder.append("] ");
                    _messageStringBuilder.append("[address = ");
                    _messageStringBuilder.append(_transformedAddress);
                    _messageStringBuilder.append("] ");
                    _messageStringBuilder.append("[exchangePattern = ");
                    _messageStringBuilder.append(_transformedExchangePattern);
                    _messageStringBuilder.append("] ");
                    _messageStringBuilder.append("[filter = ");
                    _messageStringBuilder.append(_transformedFilter);
                    _messageStringBuilder.append("] ");
                    logger.debug(_messageStringBuilder.toString());
                }
                connection = _castedModuleObject.acquireConnection(new ZeroMQTransportConnectionManager.ConnectionKey(_transformedExchangePattern, _transformedSocketOperation, _transformedAddress, _transformedFilter, false));
                if (connection == null) {
                    throw new MessagingException(CoreMessages.failedToCreate("outboundEndpoint"), (MuleEvent) null, new RuntimeException("Cannot create connection"));
                } else {
                    if (logger.isDebugEnabled()) {
                        StringBuilder _messageStringBuilder = new StringBuilder();
                        _messageStringBuilder.append("Connection has been acquired with ");
                        _messageStringBuilder.append("[id = ");
                        _messageStringBuilder.append(connection.connectionId());
                        _messageStringBuilder.append("] ");
                        logger.debug(_messageStringBuilder.toString());
                    }
                }
            } catch (Exception e) {
                throw new MessagingException(CoreMessages.failedToInvoke("outboundEndpoint"), (MuleEvent) null, e);
            } finally {
                try {
                    if (connection != null) {
                        if (logger.isDebugEnabled()) {
                            StringBuilder _messageStringBuilder = new StringBuilder();
                            _messageStringBuilder.append("Releasing the connection back into the pool [id=");
                            _messageStringBuilder.append(connection.connectionId());
                            _messageStringBuilder.append("].");
                            logger.debug(_messageStringBuilder.toString());
                        }
                        _castedModuleObject.releaseConnection(new ZeroMQTransportConnectionManager.ConnectionKey(_transformedExchangePattern, _transformedSocketOperation, _transformedAddress, _transformedFilter, false), connection);
                    }
                } catch (Exception e) {
                    throw new MessagingException(CoreMessages.failedToInvoke("outboundEndpoint"), (MuleEvent) null, e);
                }
            }
        } catch (MessagingException e) {
            flowConstruct.getExceptionListener().handleException(e, e.getEvent());
        } catch (Exception e) {
            muleContext.getExceptionListener().handleException(e);
        }
    }
}