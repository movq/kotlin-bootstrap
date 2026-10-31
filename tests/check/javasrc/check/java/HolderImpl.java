package check.java;

public class HolderImpl implements Holder {
    @Override
    public AccessorImpl getAccessor() {
        return new AccessorImpl();
    }
}
