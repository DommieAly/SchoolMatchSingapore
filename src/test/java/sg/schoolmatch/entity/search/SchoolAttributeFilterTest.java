package sg.schoolmatch.entity.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/** Unit tests for «entity» SchoolAttributeFilter (type, programme, CCA, district; OR within the category). */
class SchoolAttributeFilterTest {

    private final School catholic = TestSchools.school("catholic-high-school")
            .type("GOVERNMENT-AIDED SCH").planningArea("BISHAN")
            .programmes("Bicultural Studies Programme", "Art").ccas("BOWLING", "CHOIR").build();
    private final School peirce = TestSchools.school("peirce-secondary-school")
            .type("GOVERNMENT SCHOOL").planningArea("BISHAN")
            .programmes("Art").ccas("RUGBY", "CHOIR").build();
    private final School noData = TestSchools.school("no-data-school").build();   // type, area null; no lists

    @Test
    @Tag("FR-FILTER-02")
    @DisplayName("TC-AttrFilter-01: SCHOOL_TYPE keeps schools whose type is one of the selected values")
    void schoolType() {
        SchoolAttributeFilter filter = new SchoolAttributeFilter(AttributeCategory.SCHOOL_TYPE,
                List.of("GOVERNMENT SCHOOL"));

        assertThat(filter.matches(peirce)).isTrue();
        assertThat(filter.matches(catholic)).isFalse();
        assertThat(filter.matches(noData)).isFalse();
    }

    @Test
    @Tag("FR-FILTER-02")
    @Tag("FR-FILTER-07")
    @DisplayName("TC-AttrFilter-02: CCA keeps schools offering ANY selected CCA (OR within the category)")
    void ccaAnyOf() {
        SchoolAttributeFilter filter = new SchoolAttributeFilter(AttributeCategory.CCA, List.of("BOWLING", "RUGBY"));

        assertThat(filter.matches(catholic)).isTrue();
        assertThat(filter.matches(peirce)).isTrue();
        assertThat(filter.matches(noData)).isFalse();
    }

    @Test
    @Tag("FR-FILTER-02")
    @DisplayName("TC-AttrFilter-03: PROGRAMME keeps schools offering any selected programme")
    void programme() {
        SchoolAttributeFilter filter = new SchoolAttributeFilter(AttributeCategory.PROGRAMME,
                List.of("Bicultural Studies Programme"));

        assertThat(filter.matches(catholic)).isTrue();
        assertThat(filter.matches(peirce)).isFalse();
    }

    @Test
    @Tag("FR-FILTER-02")
    @DisplayName("TC-AttrFilter-04: DISTRICT keeps schools in a selected planning area (DC-04)")
    void district() {
        SchoolAttributeFilter bishan = new SchoolAttributeFilter(AttributeCategory.DISTRICT, List.of("BISHAN"));
        SchoolAttributeFilter tampines = new SchoolAttributeFilter(AttributeCategory.DISTRICT, List.of("TAMPINES"));

        assertThat(bishan.matches(catholic)).isTrue();
        assertThat(tampines.matches(catholic)).isFalse();
        assertThat(bishan.matches(noData)).isFalse();
    }

    @Test
    @Tag("FR-FILTER-02")
    @DisplayName("TC-AttrFilter-05: values are compared ignoring case (a hand-typed URL still works)")
    void ignoresCase() {
        SchoolAttributeFilter filter = new SchoolAttributeFilter(AttributeCategory.CCA, List.of("bowling"));

        assertThat(filter.matches(catholic)).isTrue();
    }

    @Test
    @Tag("FR-FILTER-02")
    @DisplayName("TC-AttrFilter-06: valid needs a category and at least one non-blank value")
    void validity() {
        assertThat(new SchoolAttributeFilter(AttributeCategory.CCA, List.of("CHOIR")).isValid()).isTrue();
        assertThat(new SchoolAttributeFilter(AttributeCategory.CCA, List.of()).isValid()).isFalse();
        assertThat(new SchoolAttributeFilter(AttributeCategory.CCA, null).isValid()).isFalse();
        assertThat(new SchoolAttributeFilter(AttributeCategory.CCA, List.of(" ")).isValid()).isFalse();
        assertThat(new SchoolAttributeFilter(null, List.of("CHOIR")).isValid()).isFalse();
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-AttrFilter-07: describe names the category and the values joined by 'or'")
    void describe() {
        assertThat(new SchoolAttributeFilter(AttributeCategory.CCA, List.of("BOWLING", "RUGBY")).describe())
                .isEqualTo("CCA: BOWLING or RUGBY");
        assertThat(new SchoolAttributeFilter(AttributeCategory.SCHOOL_TYPE, List.of("GOVERNMENT SCHOOL")).describe())
                .isEqualTo("School type: GOVERNMENT SCHOOL");
        assertThat(new SchoolAttributeFilter(AttributeCategory.DISTRICT, List.of("BISHAN")).describe())
                .isEqualTo("District: BISHAN");
        assertThat(new SchoolAttributeFilter(AttributeCategory.PROGRAMME, List.of("Art")).describe())
                .isEqualTo("Programme: Art");
    }

    @Test
    @Tag("FR-DATA-07")
    @DisplayName("TC-AttrFilter-08: matching never changes the school")
    void doesNotChangeSchool() {
        new SchoolAttributeFilter(AttributeCategory.CCA, List.of("BOWLING")).matches(catholic);

        assertThat(catholic.getCcas()).containsExactly("BOWLING", "CHOIR");
        assertThat(catholic.getSchoolType()).isEqualTo("GOVERNMENT-AIDED SCH");
    }
}
