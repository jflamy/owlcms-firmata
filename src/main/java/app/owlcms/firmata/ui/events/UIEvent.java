package app.owlcms.firmata.ui.events;

public class UIEvent {
    public static class ConfigsUpdated {
        private long timestamp = System.currentTimeMillis();
        
        @Override
        public String toString() {
            return "ConfigsUpdated@" + timestamp;
        }
    }
    
    public static class PlatformsUpdated {
        private long timestamp = System.currentTimeMillis();
        
        @Override
        public String toString() {
            return "PlatformsUpdated@" + timestamp;
        }
    }
}
