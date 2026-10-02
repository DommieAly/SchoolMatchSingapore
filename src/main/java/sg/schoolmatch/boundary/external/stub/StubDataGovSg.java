package sg.schoolmatch.boundary.external.stub;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import sg.schoolmatch.boundary.external.DataGovSgInterface;
import sg.schoolmatch.boundary.external.DataGovSgRecord;

/**
 * Offline {@link DataGovSgInterface} (app.external.datagovsg.mode=stub, the default). The running app never
 * needs data.gov.sg (it reads the committed snapshot), so this returns nothing. Only the import profile
 * uses the live DataGovSgClient.
 */
@Component
// Any mode except "live" (also an empty or missing value) selects the stub, so exactly one bean always exists.
@ConditionalOnExpression("!'live'.equalsIgnoreCase('${app.external.datagovsg.mode:stub}')")
public class StubDataGovSg implements DataGovSgInterface {

    @Override
    public List<DataGovSgRecord> fetchSchools() {
        return List.of();
    }

    @Override
    public List<DataGovSgRecord> fetchSchoolCcas() {
        return List.of();
    }

    @Override
    public List<DataGovSgRecord> fetchSchoolSubjects() {
        return List.of();
    }

    @Override
    public String fetchDistrictsGeoJson() {
        return "{}";
    }
}
