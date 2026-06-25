import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.buggyboi.burpcockpit.BurpCockpitApp;

public final class Extension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        new BurpCockpitApp(api).initialize();
    }
}
