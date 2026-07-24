package app.owlcms.firmata.ui;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.lumo.Lumo;

// Vaadin 25 no longer applies a theme automatically. In Vaadin 24 Lumo was the
// implicit default; loading it explicitly restores the previous appearance.
@StyleSheet(Lumo.STYLESHEET)
@CssImport("./styles/shared-styles.css")
@CssImport(value = "./styles/vaadin-text-field-styles.css", themeFor = "vaadin-text-field")
@CssImport(value = "./styles/vaadin-radio-group-styles.css", themeFor = "vaadin-radio-group")
@Push
public class AppShell implements AppShellConfigurator {
}
