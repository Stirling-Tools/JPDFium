package stirling.software.jpdfium.instance;

import java.util.function.Function;

import stirling.software.jpdfium.panama.PdfiumRuntime;

public class LoaderIdentityTask implements Function<Object, Object> {

    @Override
    public Object apply(Object input) {
        return PdfiumRuntime.class.getClassLoader();
    }
}
