package fr.imlovinit.mockito;

import static fr.imlovinit.mockito.MockitoLite.Matchers.anyString;
import static fr.imlovinit.mockito.MockitoLite.Mockito.*;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.mockito.cglib.core.CodeGenerationException;
import org.mockito.cglib.proxy.Callback;
import org.mockito.cglib.proxy.Enhancer;
import org.mockito.cglib.proxy.Factory;
import org.mockito.cglib.proxy.MethodInterceptor;
import org.mockito.cglib.proxy.MethodProxy;
import org.mockito.exceptions.base.MockitoAssertionError;
import org.mockito.exceptions.base.MockitoException;
import org.objenesis.ObjenesisStd;

public class MockitoLite {

    // @formatter:off
    
    public interface Registry {

        Object lookup(String name);
    }

    public class RegistryCacheDecorator implements Registry {

        private Registry decoratedRegistry;
        private Map<String, Object> cache = new HashMap<String, Object>();

        public RegistryCacheDecorator(Registry registry) {
            this.decoratedRegistry = registry;
        }

        public Object lookup(String name) {
            if (!cache.containsKey(name)) {
                cache.put(name, decoratedRegistry.lookup(name));
            }
            return cache.get(name);
        }

    }

    // SUT
    private RegistryCacheDecorator decorator;

    // DOC
    private Registry registry;

    @Before
    public void before() {
        registry = mock(Registry.class);

        decorator = new RegistryCacheDecorator(registry);
    }

    @Test
    public void registryShouldOnlyBeCalledOnceForTheSameName() throws Exception {
        // Given
        when(registry.lookup(anyString())).thenReturn("Julien"); // ignore matchers

        // When
        decorator.lookup("datasource");
        decorator.lookup("datasource");

        // Then
        verify(registry, times(1)).lookup("datasource");
        verify(registry, times(0)).lookup("userstore");
    }
    

    @Test
    public void mockShouldReturnTheRightValue() throws Exception {
        // Given
        when(registry.lookup("datasource")).thenReturn("BasicDataSource");
        when(registry.lookup("userstore")).thenReturn("UserStore"); 

        // Then
        assertEquals("BasicDataSource", decorator.lookup("datasource"));
        assertEquals("UserStore", decorator.lookup("userstore"));
    }
    

    /* Mockito Core */

    public static class Mockito {

        private static MockingProgress mockingProgress = MockingProgress.INSTANCE;

        public static <T> T mock(Class<T> classToMock) {
            MockHandler mockHandler = new MockHandlerImpl();
            MockMaker mockMaker = new CglibMockMaker();
            
            T mock = mockMaker.createMock(classToMock, mockHandler);

            return mock;
        }

        public static <T> OngoingStubbing<T> when(T methodCall) {
            mockingProgress.stubbingStarted();
            return mockingProgress.pullOngoingStubbing();
        }

        public static <T> T verify(T mock, VerificationMode mode) {
            mockingProgress.verificationStarted(mode);
            return mock;
        }

    }

    public static interface MockMaker {

        <T> T createMock(Class<T> classToMock, MockHandler handler);
    }
    
    
    public static class CglibMockMaker implements MockMaker {

        public <T> T createMock(Class<T> mockedType, MockHandler handler) {
            
            MethodInterceptor interceptor = new MethodInterceptorFilter(handler);
            
            Class<Factory> proxyClass = null;
            Object proxyInstance = null;
            try {
                proxyClass = createProxyClass(mockedType);
                proxyInstance = createProxy(proxyClass, interceptor);
                return mockedType.cast(proxyInstance);
            } catch (ClassCastException cce) {
                throw new MockitoException("Exception occurred while creating the mockito proxy", cce);
            } 
            
        }
        
        public Class<Factory> createProxyClass(Class<?> mockedType) {
            Enhancer enhancer = new Enhancer();
            enhancer.setUseFactory(true);
            enhancer.setSuperclass(mockedType);
            enhancer.setCallbackTypes(new Class[]{MethodInterceptor.class});
            
            try {
                return enhancer.createClass(); 
            } catch (CodeGenerationException e) {
                throw new MockitoException("Mockito cannot mock this class: " + mockedType);
            }
        }
        
        private Object createProxy(Class<Factory> proxyClass, final MethodInterceptor interceptor) {
            ObjenesisStd objenesis = new ObjenesisStd();
            Factory proxy = objenesis.newInstance(proxyClass);
            proxy.setCallbacks(new Callback[] {interceptor});
            return proxy;
        }

    }
    
    public static class Invocation {

        private final Object mock;
        private final Method method;
        private final Object[] arguments;
        private final MethodProxy methodProxy;

        public Invocation(Object mock, Method method, Object[] args, MethodProxy methodProxy) {
            this.method = method;
            this.mock = mock;
            this.methodProxy = methodProxy;
            this.arguments = args;
        }
        
        public Object getMock() {
            return mock;
        }

        public Method getMethod() {
            return method;
        }

        public Object[] getArguments() {
            return arguments;
        }

    }
    
    
    
    public static class InvocationMatcher {

        private final Invocation invocation;
        private final List<Matcher> matchers;

        public InvocationMatcher(Invocation invocation, List<Matcher> matchers) {
            this.invocation = invocation;
            if (matchers.isEmpty()) {
                this.matchers = argumentsToMatchers(invocation.getArguments());
            } else {
                this.matchers = matchers;
            }
        }
        
        public static List<Matcher> argumentsToMatchers(Object[] arguments) {
            List<Matcher> matchers = new ArrayList<Matcher>(arguments.length);
            for (Object arg : arguments) {
                matchers.add(new Equals(arg));
            }
            return matchers;
        }
        
        public Invocation getInvocation() {
            return this.invocation;
        }
        
        public List<Matcher> getMatchers() {
            return this.matchers;
        }
        
        public boolean matches(Invocation actual) {
            return invocation.getMock() == actual.getMock()
                    && hasSameMethod(actual)
                    && hasMatchingArguments(this, actual);
        }

        private boolean hasSameMethod(Invocation candidate) {
            Method m1 = this.getInvocation().getMethod();
            Method m2 = candidate.getMethod();
            return m1.equals(m2);
        }

        private boolean hasMatchingArguments(InvocationMatcher invocationMatcher, Invocation actual) {
            Object[] actualArgs = actual.getArguments();
            if (actualArgs.length != invocationMatcher.getMatchers().size()) {
                return false;
            }
            for (int i = 0; i < actualArgs.length; i++) {
                if (!invocationMatcher.getMatchers().get(i).matches(actualArgs[i])) {
                    return false;
                }
            }
            return true;
        }

    }
    
    
    public static class MethodInterceptorFilter implements MethodInterceptor {

        private final MockHandler handler;

        public MethodInterceptorFilter(MockHandler handler) {
            this.handler = handler;
        }

        public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy)
                throws Throwable {

            Invocation invocation = new Invocation(proxy, method, args, methodProxy);
            return handler.handle(invocation);
        }
    }
    
    
    public static interface MockHandler {
        Object handle(Invocation invocation) throws Throwable;
    }

    
    public static class MockHandlerImpl<T> implements MockHandler {

        private MockingProgress mockingProgress = MockingProgress.INSTANCE;
        private InvocationContainer invocationContainer;

        public MockHandlerImpl() {
            this.invocationContainer = new InvocationContainer();
        }

        public Object handle(Invocation invocation) throws Throwable {
            
            VerificationMode verificationMode = mockingProgress.pullVerificationMode();
            
            List<Matcher> lastMatchers = mockingProgress.pullLocalizedMatchers();
            InvocationMatcher invocationWithMatchers = new InvocationMatcher(invocation, lastMatchers);
            
            if (verificationMode != null) { // verify?
                VerificationData data = new VerificationData(invocationContainer, invocationWithMatchers);
                verificationMode.verify(data);
                return null;
            }  

            invocationContainer.setInvocationForPotentialStubbing(invocationWithMatchers);
            OngoingStubbing<T> ongoingStubbing = new OngoingStubbing<T>(invocationContainer);
            mockingProgress.reportOngoingStubbing(ongoingStubbing);
            
            // look for existing answer for this invocation
            Answer answer = invocationContainer.findAnswerFor(invocation);
            
            if (answer == null) { // when?
                return null;
            } else { // called by SUT
                return answer.answer(invocation);
            }
        }
        
    }
    
    
    public static interface Answer<T> {
        T answer(Invocation invocation) throws Throwable;
    }
    
    
    public static class Returns implements Answer<Object> {

        private final Object value;

        public Returns(Object value) {
            this.value = value;
        }

        public Object answer(Invocation invocation) throws Throwable {
            return value;
        }

    }
    
    public static class ThrowsException implements Answer<Object> {

        private final Throwable throwable;

        public ThrowsException(Throwable throwable) {
            this.throwable = throwable;
        }

        public Object answer(Invocation invocation) throws Throwable {
            throw throwable;
        }

    }

    public static class InvocationContainer {
    
        private final Map<InvocationMatcher, Answer> stubbed = new HashMap<InvocationMatcher, Answer>();
        private InvocationMatcher invocationForStubbing;
        private LinkedList<Invocation> registeredInvocations = new LinkedList<Invocation>();
    
        public void setInvocationForPotentialStubbing(InvocationMatcher invocationMatcher) {
            registeredInvocations.add(invocationMatcher.getInvocation());
            invocationForStubbing = invocationMatcher;
        }
    
        public void addAnswer(Answer answer) {
            registeredInvocations.removeLast();
            stubbed.put(invocationForStubbing, answer);
            invocationForStubbing = null;
        }
        
        public List<Invocation> getInvocations() {
            return registeredInvocations;
        }
    
        public Answer findAnswerFor(Invocation invocation) {
            for (Entry<InvocationMatcher, Answer> eachEntry : stubbed.entrySet()) {
                InvocationMatcher eachInvocationMatcher = eachEntry.getKey();
                Answer eachAnswer = eachEntry.getValue();
                if (eachInvocationMatcher.matches(invocation)) {
                    return eachAnswer;
                }
            }
    
            return null;
        }
        
    }
    

    public static class OngoingStubbing<T> {

        private final InvocationContainer invocationContainer;

        public OngoingStubbing(InvocationContainer invocationContainer) {
            this.invocationContainer = invocationContainer;
        }

        public OngoingStubbing<T> thenReturn(T value) {
            return thenAnswer(new Returns(value));
        }

        public OngoingStubbing<T> thenThrow(Throwable throwable) {
            return thenAnswer(new ThrowsException(throwable));
        }

        public OngoingStubbing<T> thenAnswer(Answer<?> answer) {
            invocationContainer.addAnswer(answer);
            return this;
        }
    }

    
    /* Verification */

    public static VerificationMode times(int wantedNumberOfInvocations) {
        return new Times(wantedNumberOfInvocations);
    }
    

    public static class VerificationData {

        private final InvocationMatcher wanted;
        private final InvocationContainer invocations;

        public VerificationData(InvocationContainer invocations, InvocationMatcher wanted) {
            this.invocations = invocations;
            this.wanted = wanted;
        }

        public List<Invocation> getAllInvocations() {
            return invocations.getInvocations();
        }

        public InvocationMatcher getWanted() {
            return wanted;
        }
    }
    

    public interface VerificationMode {

        void verify(VerificationData data);

    }

    
    public static class Times implements VerificationMode {

        final int wantedCount;

        public Times(int wantedNumberOfInvocations) {
            this.wantedCount = wantedNumberOfInvocations;
        }

        public void verify(VerificationData data) {
            int actualCount = 0;
            for (Invocation eachInvocation : data.getAllInvocations()) {
                if (data.getWanted().matches(eachInvocation)) {
                    actualCount++;
                }
            }
            if (actualCount != wantedCount) {
                throw new MockitoAssertionError("Actual: " + actualCount + ", expected: " + wantedCount); 
            }
        }

    }

    
    /* Argument Matchers */

    public static abstract class ArgumentMatcher<T> extends BaseMatcher<T> { // Hamcrest

        public abstract boolean matches(Object argument);

    }
    

    public static class MockingProgress {

        public static MockingProgress INSTANCE = new MockingProgress();

        private final List<Matcher> matcherStack = new ArrayList<Matcher>();
        private VerificationMode verificationMode;
        @SuppressWarnings("rawtypes")
        private OngoingStubbing ongoingStubbing;


        public void reportMatcher(Matcher matcher) {
            matcherStack.add(matcher);
        }
        
        public void reportOngoingStubbing(OngoingStubbing ongoingStubbing) {
            this.ongoingStubbing = ongoingStubbing;
        }

        public void stubbingStarted() {

        }
        
        public void verificationStarted(VerificationMode verify) {
            ongoingStubbing = null;
            verificationMode = verify;
        }
        
        public List<Matcher> pullLocalizedMatchers() {
            if (matcherStack.isEmpty()) {
                return Collections.emptyList();
            }
            
            List<Matcher> matchers = new ArrayList<Matcher>(matcherStack);
            matcherStack.clear();
            return matchers;
        }
        

        public OngoingStubbing pullOngoingStubbing() {
            OngoingStubbing temp = ongoingStubbing;
            ongoingStubbing = null;
            return temp;
        }
        
        public VerificationMode pullVerificationMode() {
            if (verificationMode == null) {
                return null;
            }
            
            VerificationMode temp = verificationMode;
            verificationMode = null;
            return temp;
        }
    }

    public static class Matchers {

        public static String anyString() {
            MockingProgress.INSTANCE.reportMatcher(new Any());
            return "";
        }
        
        public static <T> T eq(T value) {
            MockingProgress.INSTANCE.reportMatcher(new Equals(value));
            return value;
        }
        
    }

    
    public static class Any<T> extends ArgumentMatcher<T> {

        @Override
        public boolean matches(Object actual) {
            return true;
        }

        public void describeTo(Description description) {
            description.appendText("<any>");
        }
    }
    
    
    public static class Equals<T> extends ArgumentMatcher<T> {

        private final Object wanted;

        public Equals(Object wanted) {
            this.wanted = wanted;
        }
        
        @Override
        public boolean matches(Object actual) {
            return wanted == actual;
        }

        public void describeTo(Description description) {
            description.appendText("<any>");
        }
        
    }


}
