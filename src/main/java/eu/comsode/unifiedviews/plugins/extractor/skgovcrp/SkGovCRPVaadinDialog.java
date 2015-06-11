package eu.comsode.unifiedviews.plugins.extractor.skgovcrp;

import eu.unifiedviews.dpu.config.DPUConfigException;
import eu.unifiedviews.helpers.dpu.vaadin.dialog.AbstractDialog;

/**
 * Vaadin configuration dialog .
 */
public class SkGovCRPVaadinDialog extends AbstractDialog<SkGovCRPConfig_V1> {

    public SkGovCRPVaadinDialog() {
        super(SkGovCRP.class);
    }

    @Override
    public void setConfiguration(SkGovCRPConfig_V1 c) throws DPUConfigException {

    }

    @Override
    public SkGovCRPConfig_V1 getConfiguration() throws DPUConfigException {
        final SkGovCRPConfig_V1 c = new SkGovCRPConfig_V1();

        return c;
    }

    @Override
    public void buildDialogLayout() {
    }

}
