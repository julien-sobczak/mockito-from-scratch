package fr.imlovinit.mockito;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class Demo {

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
    @Mock
    private Registry registry;

    @Before
    public void before() {
	MockitoAnnotations.initMocks(this);

	decorator = new RegistryCacheDecorator(registry);
    }

    @Test
    public void basicUsage() throws Exception {
	// Given
	when(registry.lookup(anyString())).thenReturn(new Object());

	// When
	decorator.lookup("datasource");
	decorator.lookup("datasource");

	// Then
	verify(registry, times(1)).lookup("datasource");
    }

}
