package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.entity.search.Filter;
import sg.schoolmatch.entity.search.ProximityFilter;
import sg.schoolmatch.entity.search.PsleScoreFilter;
import sg.schoolmatch.entity.search.SchoolAttributeFilter;
import sg.schoolmatch.entity.search.SortOrder;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;
import sg.schoolmatch.support.ExternalFailures;
import sg.schoolmatch.support.LogCapture;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of the boundary class SchoolSearchUI (example of a UI test: MockMvc, no database).
 * <p>
 * {@code @WebMvcTest} starts only the web layer: this UI class, the shared advice classes and the
 * interceptor. The control it calls is a mock, so the test checks just the page: which control call
 * is made, what goes into the model and what the HTML shows.
 * <p>
 * Copy these annotations for any UI test. {@code @WebMvcTest} skips plain {@code @Component} and
 * {@code @ConfigurationProperties} classes, so the test adds the two the shared layout needs:
 * {@link AppProperties} (page size, footer settings) and {@link SessionCookie} (used by the layout advice
 * and AuthInterceptor). The controls those classes call are {@code @MockitoBean}s below.
 * <p>
 * Filter tests (use case Filter Schools): FilterController is a mock whose answer applies the filters with the
 * real entity rule ({@link #applyLikeTheControl}), so the page sees realistic results.
 */
@WebMvcTest(SchoolSearchUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SessionCookie.class, ReferenceLocationStore.class})
@ActiveProfiles("test")
class SchoolSearchUITest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @MockitoBean
    private SchoolController schoolController;

    @MockitoBean
    private FilterController filterController;

    @MockitoBean
    private ProfileController profileController;

    // Needed by the shared layout (LayoutModelAdvice footer, AuthInterceptor), not by this page.
    // Unstubbed mocks return null; the footer then says "Dataset not loaded".
    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    private final School catholic = TestSchools.school("catholic-high-school")
            .name("CATHOLIC HIGH SCHOOL").type("GOVERNMENT-AIDED SCH").planningArea("BISHAN").build();
    private final School anglican = TestSchools.school("anglican-high-school")
            .name("ANGLICAN HIGH SCHOOL").planningArea("TAMPINES").build();

    @Test
    @Tag("FR-SEARCH-03")
    @DisplayName("TC-SEARCH-03-01: each result shows a school summary that links to its details page")
    void submitSearch_showsSummaryCards() throws Exception {
        when(schoolController.searchSchools("high"))
                .thenReturn(new CurrentResultSet("high", List.of(anglican, catholic)));

        mvc.perform(get("/schools").param("q", "high"))
                .andExpect(status().isOk())
                .andExpect(view().name("school-search"))
                .andExpect(model().attribute("term", "high"))
                .andExpect(model().attribute("totalCount", 2))
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("href=\"/schools/catholic-high-school\"")));
        verify(schoolController).searchSchools("high");
    }

    @Test
    @Tag("FR-SEARCH-04")
    @DisplayName("TC-SEARCH-04-05: opening /schools without a term lists all schools on page 1")
    void submitSearch_noTerm_showsFirstPage() throws Exception {
        when(schoolController.searchSchools(null))
                .thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));

        mvc.perform(get("/schools"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("term", ""))
                .andExpect(model().attribute("schools", List.of(anglican, catholic)))
                .andExpect(model().attribute("page", 1))
                .andExpect(model().attribute("totalPages", 1))
                .andExpect(model().attribute("baseUrl", "/schools"));
    }

    @Test
    @Tag("FR-SEARCH-04")
    @DisplayName("TC-SEARCH-04-06: page 2 shows the schools after the first page")
    void submitSearch_page2_showsTheRest() throws Exception {
        int pageSize = props.search().pageSize();
        List<School> many = manySchools(pageSize + 5);
        when(schoolController.searchSchools("school")).thenReturn(new CurrentResultSet("school", many));

        mvc.perform(get("/schools").param("q", "school").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", many.subList(pageSize, pageSize + 5)))
                .andExpect(model().attribute("totalCount", pageSize + 5))
                .andExpect(model().attribute("page", 2))
                .andExpect(model().attribute("totalPages", 2))
                .andExpect(model().attribute("baseUrl", "/schools?q=school"));
    }

    @Test
    @Tag("FR-SEARCH-04")
    @DisplayName("TC-SEARCH-04-09: the pager links keep a '+' in the term (encoded as %2B, not read back as a space)")
    void submitSearch_plusInTerm_pagerKeepsTerm() throws Exception {
        List<School> many = manySchools(props.search().pageSize() + 1);
        when(schoolController.searchSchools("a+b")).thenReturn(new CurrentResultSet("a+b", many));

        mvc.perform(get("/schools").param("q", "a+b"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("baseUrl", "/schools?q=a%2Bb"))
                .andExpect(content().string(containsString("href=\"/schools?q=a%2Bb&amp;page=2\"")));
    }

    @Test
    @Tag("FR-SEARCH-04")
    @DisplayName("TC-SEARCH-04-07: a page number past the end shows the last page")
    void submitSearch_pageTooHigh_showsLastPage() throws Exception {
        when(schoolController.searchSchools(null))
                .thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));

        mvc.perform(get("/schools").param("page", "99"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("page", 1))
                .andExpect(model().attribute("schools", hasSize(2)));
    }

    @Test
    @Tag("FR-SEARCH-04")
    @DisplayName("TC-SEARCH-04-10: a non-numeric page number shows page 1, not an error")
    void submitSearch_pageNotANumber_showsFirstPage() throws Exception {
        when(schoolController.searchSchools(null))
                .thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));

        mvc.perform(get("/schools").param("page", "abc"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("page", 1));
    }

    @Test
    @Tag("FR-SEARCH-05")
    @DisplayName("TC-SEARCH-05-02: an empty result shows the \"No schools match\" message")
    void submitSearch_noMatches_showsMessage() throws Exception {
        when(schoolController.searchSchools("xyz")).thenReturn(new CurrentResultSet("xyz", List.of()));

        mvc.perform(get("/schools").param("q", "xyz"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 0))
                .andExpect(content().string(containsString("No schools match")));
    }

    @Test
    @Tag("FR-SEARCH-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-SEARCH-01-06: a too-long term shows the field error and no results")
    void submitSearch_invalidTerm_showsFieldError() throws Exception {
        String tooLong = "a".repeat(101);
        when(schoolController.searchSchools(tooLong))
                .thenThrow(new InvalidInputException("q", "Enter 1–100 characters"));

        mvc.perform(get("/schools").param("q", tooLong))
                .andExpect(status().isOk())
                .andExpect(view().name("school-search"))
                .andExpect(model().attributeExists("fieldErrors"))
                .andExpect(model().attributeDoesNotExist("schools"))
                .andExpect(content().string(containsString("Enter 1–100 characters")))
                .andExpect(content().string(not(containsString("No schools match"))));
    }

    // ---- Filter Schools (UC #08) on the results page -------------------------------------------------------

    private final ReferenceLocation bishanMrt =
            new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "BISHAN MRT");

    /** What FilterController.applyFilters does without a travel filter: entity AND/OR rule on the search result. */
    private static CurrentResultSet applyLikeTheControl(CurrentResultSet results, List<Filter> filters) {
        CurrentResultSet filtered = results.copyWith(results.getUnfilteredSchools(), SortOrder.NAME_ASC);
        filters.forEach(filtered::addFilter);
        filtered.applyFilters();
        return filtered;
    }

    private void filtersApplyForReal() {
        when(filterController.applyFilters(any(), anyList()))
                .thenAnswer(call -> applyLikeTheControl(call.getArgument(0), call.getArgument(1)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<Filter>> filtersCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private MockHttpSession sessionWithStart() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT, bishanMrt);
        return session;
    }

    @Test
    @Tag("FR-FILTER-01")
    @Tag("FR-FILTER-08")
    @DisplayName("TC-FILTER-08-02: the page applies the URL's filters and shows each one as a chip with a remove link")
    void filters_appliedAndShownAsChips() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        filtersApplyForReal();

        mvc.perform(get("/schools").param("district", "BISHAN").param("type", "GOVERNMENT-AIDED SCH"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 1))
                .andExpect(model().attribute("schools", List.of(catholic)))
                .andExpect(content().string(containsString("District: BISHAN")))
                .andExpect(content().string(containsString("href=\"/schools?type=GOVERNMENT-AIDED%20SCH\"")))
                .andExpect(content().string(containsString("href=\"/schools?district=BISHAN\"")))
                .andExpect(content().string(containsString("Clear all")))
                .andExpect(content().string(containsString(
                        "href=\"/schools/filter?type=GOVERNMENT-AIDED%20SCH&amp;district=BISHAN\"")))
                .andExpect(content().string(containsString(
                        "href=\"/schools/map?type=GOVERNMENT-AIDED%20SCH&amp;district=BISHAN\"")));

        ArgumentCaptor<List<Filter>> filters = filtersCaptor();
        verify(filterController).applyFilters(any(), filters.capture());
        assertThat(filters.getValue()).hasSize(2).allMatch(f -> f instanceof SchoolAttributeFilter);
    }

    @Test
    @Tag("FR-FILTER-03")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FILTER-03-11: an invalid PSLE score is reported and the other filters still apply")
    void filters_invalidPsle_otherFiltersStillApply() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        filtersApplyForReal();

        mvc.perform(get("/schools").param("psle", "abc").param("district", "TAMPINES"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("fieldErrors", hasEntry("psle", "Enter a whole number from 4 to 32")))
                .andExpect(model().attribute("schools", List.of(anglican)))
                .andExpect(content().string(containsString("Enter a whole number from 4 to 32")));
    }

    @Test
    @Tag("FR-FILTER-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FILTER-02-04: a value the control rejects is reported and that filter is dropped (AF-2)")
    void filters_unknownValue_droppedAndReported() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        when(filterController.applyFilters(any(), anyList()))
                .thenThrow(new InvalidInputException("cca", "Unknown CCA: UNDERWATER HOCKEY"))
                .thenAnswer(call -> applyLikeTheControl(call.getArgument(0), call.getArgument(1)));

        mvc.perform(get("/schools").param("cca", "UNDERWATER HOCKEY").param("district", "BISHAN"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("fieldErrors", hasEntry("cca", "Unknown CCA: UNDERWATER HOCKEY")))
                .andExpect(model().attribute("schools", List.of(catholic)));

        ArgumentCaptor<List<Filter>> filters = filtersCaptor();
        verify(filterController, times(2)).applyFilters(any(), filters.capture());
        assertThat(filters.getAllValues().get(1)).singleElement()
                .satisfies(f -> assertThat(((SchoolAttributeFilter) f).getSelectedValues()).containsExactly("BISHAN"));
    }

    @Test
    @Tag("FR-FILTER-08")
    @Tag("FR-FILTER-04")
    @DisplayName("TC-FILTER-08-04: a distance filter without a starting point is listed as not applied, not as an active chip (AF-3)")
    void filters_rejectedFilterIsNotAnActiveChip() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));

        mvc.perform(get("/schools").param("radiusKm", "3"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("chips", List.of()))
                .andExpect(model().attribute("notAppliedChips", hasSize(1)))
                .andExpect(model().attribute("hasFilters", false))
                .andExpect(content().string(containsString("Not applied:")))
                .andExpect(content().string(containsString("Within 3 km")))
                .andExpect(content().string(not(containsString("match your filters"))));
        verify(filterController, never()).applyFilters(any(), anyList());
    }

    @Test
    @Tag("FR-FILTER-08")
    @Tag("FR-FILTER-02")
    @DisplayName("TC-FILTER-08-05: when the control rejects a category, only the applied filters are chips and counted (step 9)")
    void filters_chipsOnlyForAppliedFilters() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        when(filterController.applyFilters(any(), anyList()))
                .thenThrow(new InvalidInputException("district", "Unknown district: MARS"))
                .thenAnswer(call -> applyLikeTheControl(call.getArgument(0), call.getArgument(1)));

        org.springframework.test.web.servlet.MvcResult result =
                mvc.perform(get("/schools").param("district", "MARS").param("psle", "12"))
                        .andExpect(status().isOk())
                        .andExpect(model().attribute("hasFilters", true))
                        .andReturn();
        String html = result.getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        List<sg.schoolmatch.boundary.ui.support.FilterParams.FilterChip> chips =
                (List<sg.schoolmatch.boundary.ui.support.FilterParams.FilterChip>)
                        result.getModelAndView().getModel().get("chips");
        assertThat(chips).extracting(sg.schoolmatch.boundary.ui.support.FilterParams.FilterChip::param)
                .containsExactly("psle");
        assertThat(html).contains("Unknown district: MARS").contains("Not applied:").contains("District: MARS");
    }

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-FILTER-07-04: filters that match nothing show the no-match message and keep the chips (AF-5)")
    void filters_noMatches() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        filtersApplyForReal();

        mvc.perform(get("/schools").param("district", "WOODLANDS"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 0))
                .andExpect(content().string(containsString("No schools match all your filters")))
                .andExpect(content().string(containsString("District: WOODLANDS")))
                .andExpect(content().string(containsString("href=\"/schools\"")));
    }

    @Test
    @Tag("FR-FILTER-04")
    @Tag("FR-FILTER-05")
    @DisplayName("TC-FILTER-05-07: the distance filter uses the starting point from the session")
    void filters_distanceUsesSessionLocation() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        filtersApplyForReal();

        mvc.perform(get("/schools").param("radiusKm", "3").session(sessionWithStart()))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("fieldErrors"));

        ArgumentCaptor<List<Filter>> filters = filtersCaptor();
        verify(filterController).applyFilters(any(), filters.capture());
        assertThat(filters.getValue()).singleElement().isInstanceOf(ProximityFilter.class)
                .satisfies(f -> assertThat(((ProximityFilter) f).getReferenceLocation()).isSameAs(bishanMrt));
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FILTER-06-08: mode and time without travel=1 do not start the travel-time filter")
    void filters_travelNeedsExplicitClick() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));

        mvc.perform(get("/schools").param("mode", "WALK").param("maxMin", "30").session(sessionWithStart()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 2));

        verify(filterController, never()).applyFilters(any(), anyList());
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FILTER-06-09: when the routing service fails, the page keeps the other filters' results and says so (EX-1)")
    void filters_travelUnavailable() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        when(filterController.applyFilters(any(), anyList()))
                .thenThrow(new ExternalServiceUnavailableException("Google Routes", null))
                .thenAnswer(call -> applyLikeTheControl(call.getArgument(0), call.getArgument(1)));

        mvc.perform(get("/schools").param("district", "BISHAN").param("travel", "1").param("mode", "TRANSIT")
                        .param("maxMin", "30").session(sessionWithStart()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", List.of(catholic)))
                .andExpect(content().string(containsString("Travel-time filter is temporarily unavailable")));

        ArgumentCaptor<List<Filter>> filters = filtersCaptor();
        verify(filterController, times(2)).applyFilters(any(), filters.capture());
        assertThat(filters.getAllValues().get(0)).anyMatch(f -> f instanceof TransportationFilter);
        assertThat(filters.getAllValues().get(1)).noneMatch(f -> f instanceof TransportationFilter);
    }

    @Test
    @Tag("FR-FILTER-06")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-FILTER-06-12: a travel-time filter refused by the app's daily limit writes one WARN line saying so")
    void filters_travelUnavailable_logsOneWarning() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        when(filterController.applyFilters(any(), anyList()))
                .thenThrow(ExternalFailures.dailyLimit("route-matrix-elements", 200, 147, 266))
                .thenAnswer(call -> applyLikeTheControl(call.getArgument(0), call.getArgument(1)));

        try (LogCapture log = LogCapture.of(sg.schoolmatch.boundary.ui.support.SearchFilterPipeline.class)) {
            mvc.perform(get("/schools").param("district", "BISHAN").param("travel", "1").param("mode", "TRANSIT")
                            .param("maxMin", "30").session(sessionWithStart()))
                    .andExpect(content().string(containsString("Travel-time filter is temporarily unavailable")));

            assertThat(log.warnings()).singleElement().asString()
                    .startsWith("Travel-time filter unavailable: Google route-matrix-elements: app daily limit reached")
                    .contains("200 of 266").contains("147 more");
        }
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FILTER-06-10: after the travel-time filter ran, cards show minutes and sorting by travel time is offered")
    void filters_travelShowsMinutes() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        when(filterController.applyFilters(any(), anyList())).thenAnswer(call -> {
            CurrentResultSet filtered = applyLikeTheControl(call.getArgument(0), List.of());
            filtered.setCommuteMinutes(Map.of("catholic-high-school", 17));
            return filtered;
        });

        mvc.perform(get("/schools").param("travel", "1").param("mode", "TRANSIT").param("maxMin", "30")
                        .session(sessionWithStart()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("17 min by public transport")))
                .andExpect(content().string(containsString("sort=COMMUTE_ASC")))
                .andExpect(content().string(containsString("Within 30 min by public transport")));
    }

    @Test
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-SEARCH-06-08: sort=DISTANCE_ASC is passed to sortResults; cards show the distance")
    void sort_byDistance() throws Exception {
        CurrentResultSet found = new CurrentResultSet(null, List.of(anglican, catholic));
        when(schoolController.searchSchools(null)).thenReturn(found);
        when(schoolController.sortResults(any(), eq(SortOrder.DISTANCE_ASC)))
                .thenAnswer(call -> ((CurrentResultSet) call.getArgument(0))
                        .copyWith(List.of(catholic, anglican), SortOrder.DISTANCE_ASC));

        mvc.perform(get("/schools").param("sort", "DISTANCE_ASC").session(sessionWithStart()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", List.of(catholic, anglican)))
                .andExpect(model().attribute("sortOrder", SortOrder.DISTANCE_ASC))
                .andExpect(content().string(containsString("0.0 km")))
                .andExpect(content().string(containsString("nearest first")));

        assertThat(found.getReferenceLocation()).isSameAs(bishanMrt);   // the control sorts from this point
    }

    @Test
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-SEARCH-06-09: a sort order that is not available falls back to A–Z with a message")
    void sort_notAvailable() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        when(schoolController.sortResults(any(), eq(SortOrder.COMMUTE_ASC)))
                .thenAnswer(call -> ((CurrentResultSet) call.getArgument(0))
                        .copyWith(List.of(anglican, catholic), SortOrder.NAME_ASC));

        mvc.perform(get("/schools").param("sort", "COMMUTE_ASC"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sortOrder", SortOrder.NAME_ASC))
                .andExpect(content().string(containsString("Sorting by travel time needs the travel-time filter")));
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-FILTER-03-12: a logged-in user's primary school decides the affiliated PSLE range (DC-22)")
    void psle_usesProfilePrimarySchool() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        filtersApplyForReal();
        UserProfile profile = mock(UserProfile.class);
        when(profile.getPrimarySchool()).thenReturn("ROSYTH SCHOOL");
        when(profileController.getProfile("session-123")).thenReturn(profile);

        mvc.perform(get("/schools").param("psle", "12").param("pg", "2")
                        .cookie(new Cookie(props.session().cookieName(), "session-123")))
                .andExpect(status().isOk());

        ArgumentCaptor<List<Filter>> filters = filtersCaptor();
        verify(filterController).applyFilters(any(), filters.capture());
        assertThat(filters.getValue()).singleElement().isInstanceOf(PsleScoreFilter.class)
                .satisfies(f -> assertThat(((PsleScoreFilter) f).getPrimarySchool()).isEqualTo("ROSYTH SCHOOL"))
                .satisfies(f -> assertThat(((PsleScoreFilter) f).getPostingGroup()).isEqualTo(2));
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-FILTER-03-13: an expired login on the public results page counts as a guest (non-affiliated range)")
    void psle_expiredSessionIsGuest() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));
        filtersApplyForReal();
        when(profileController.getProfile("expired")).thenThrow(new NotAuthenticatedException());

        mvc.perform(get("/schools").param("psle", "12").cookie(new Cookie(props.session().cookieName(), "expired")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("posting group 3 is used")));

        ArgumentCaptor<List<Filter>> filters = filtersCaptor();
        verify(filterController).applyFilters(any(), filters.capture());
        assertThat(((PsleScoreFilter) filters.getValue().getFirst()).getPrimarySchool()).isNull();
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-FILTER-03-14: without a PSLE filter the profile is not read")
    void noPsle_noProfileLookup() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(new CurrentResultSet(null, List.of(anglican, catholic)));

        mvc.perform(get("/schools").cookie(new Cookie(props.session().cookieName(), "session-123")))
                .andExpect(status().isOk());

        verify(profileController, never()).getProfile(any());
    }

    /** "Test School 01" … "Test School nn", already in A–Z order. */
    private static List<School> manySchools(int count) {
        List<School> schools = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String number = String.format("%02d", i);
            schools.add(TestSchools.named("test-school-" + number, "TEST SCHOOL " + number));
        }
        return schools;
    }
}
