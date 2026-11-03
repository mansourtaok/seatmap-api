package com.seatmap.api.layout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LayoutApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void emptyDatabase() {
		this.jdbc.execute("TRUNCATE layout CASCADE");
	}

	@Test
	void createsALayoutWithAServerAssignedId() throws Exception {
		MvcResult result = this.mvc.perform(post("/api/layouts").contentType(MediaType.APPLICATION_JSON)
			.content(TestDocuments.load("small-theater").toString()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
			.andReturn();
		JsonNode created = TestDocuments.JSON.readTree(result.getResponse().getContentAsString());
		String id = created.get("id").asString();
		assertThat(id).isNotEqualTo("venue-1");
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/layouts/" + id);
		assertThat(created.get("objects").size()).isEqualTo(121);

		this.mvc.perform(get("/api/layouts/" + id))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
			.andExpect(jsonPath("$.name").value("Small Theater"))
			.andExpect(jsonPath("$.objects", hasSize(121)));
		this.mvc.perform(get("/api/layouts"))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].revision").value(0))
			.andExpect(jsonPath("$[0].latestVersion").doesNotExist());
	}

	@Test
	void rejectsAnInvalidDocumentListingItsProblems() throws Exception {
		ObjectNode document = TestDocuments.stand();
		TestDocuments.object(document, "seat-n1").put("ticketCategoryId", "cat-gone");
		this.mvc.perform(post("/api/layouts").contentType(MediaType.APPLICATION_JSON).content(document.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("The layout document is invalid"))
			.andExpect(jsonPath("$.problems",
					hasItem("Object 'seat-n1' uses ticket category \"cat-gone\", which doesn't exist")));
	}

	@Test
	void savesADraftOnlyFromItsCurrentRevision() throws Exception {
		ObjectNode document = create(TestDocuments.stand());
		String id = document.get("id").asString();
		document.put("name", "Arena, renamed");

		this.mvc.perform(put("/api/layouts/" + id + "/draft").header(HttpHeaders.IF_MATCH, "\"0\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content(document.toString()))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

		// A second tab still holding revision 0 must not overwrite revision 1.
		this.mvc.perform(put("/api/layouts/" + id + "/draft").header(HttpHeaders.IF_MATCH, "\"0\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content(document.toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("The layout is at revision 1, not 0; reload it before saving"));

		this.mvc.perform(get("/api/layouts/" + id)).andExpect(jsonPath("$.name").value("Arena, renamed"));
	}

	@Test
	void publishesImmutableVersionsWithTheirInventory() throws Exception {
		ObjectNode document = create(TestDocuments.stand());
		String id = document.get("id").asString();

		this.mvc.perform(post("/api/layouts/" + id + "/versions"))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/layouts/" + id + "/versions/1"))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.seatCount").value(2))
			.andExpect(jsonPath("$.totalCapacity").value(2));

		this.mvc.perform(get("/api/layouts/" + id + "/versions/1/inventory"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categories[0].defaultPrice").value(120.5))
			.andExpect(jsonPath("$.units", hasSize(2)))
			.andExpect(jsonPath("$.units[0].unitId").value("seat-n1"))
			.andExpect(jsonPath("$.units[0].label").value("North Stand · Block N1 · Row 3 · Seat 7"))
			.andExpect(jsonPath("$.units[1].designStatus").value("blocked"));

		// Editing the draft afterwards leaves version 1 as it was.
		TestDocuments.object(document, "seat-n2").remove("status");
		this.mvc.perform(put("/api/layouts/" + id + "/draft").header(HttpHeaders.IF_MATCH, "\"0\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content(document.toString())).andExpect(status().isNoContent());
		this.mvc.perform(post("/api/layouts/" + id + "/versions").header(HttpHeaders.IF_MATCH, "\"1\""))
			.andExpect(jsonPath("$.version").value(2));

		this.mvc.perform(get("/api/layouts/" + id + "/versions/1/inventory"))
			.andExpect(jsonPath("$.units[1].designStatus").value("blocked"));
		this.mvc.perform(get("/api/layouts/" + id + "/versions/2/inventory"))
			.andExpect(jsonPath("$.units[1].designStatus").doesNotExist());
		this.mvc.perform(get("/api/layouts/" + id + "/versions/1"))
			.andExpect(jsonPath("$.objects[2].status").value("blocked"));
		this.mvc.perform(get("/api/layouts/" + id + "/versions"))
			.andExpect(jsonPath("$[*].version").value(org.hamcrest.Matchers.contains(2, 1)));
		this.mvc.perform(get("/api/layouts")).andExpect(jsonPath("$[0].latestVersion").value(2));
	}

	@Test
	void refusesToPublishADraftThatChangedSinceTheCallerSawIt() throws Exception {
		String id = create(TestDocuments.stand()).get("id").asString();
		this.mvc.perform(post("/api/layouts/" + id + "/versions").header(HttpHeaders.IF_MATCH, "\"5\""))
			.andExpect(status().isConflict());
	}

	@Test
	void deletesOnlyLayoutsThatWereNeverPublished() throws Exception {
		String draftOnly = create(TestDocuments.stand()).get("id").asString();
		this.mvc.perform(delete("/api/layouts/" + draftOnly)).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/layouts/" + draftOnly)).andExpect(status().isNotFound());

		String published = create(TestDocuments.stand()).get("id").asString();
		this.mvc.perform(post("/api/layouts/" + published + "/versions")).andExpect(status().isCreated());
		this.mvc.perform(delete("/api/layouts/" + published)).andExpect(status().isConflict());
	}

	@Test
	void storesFloorPlanImagesOnce() throws Exception {
		String id = create(TestDocuments.stand()).get("id").asString();
		byte[] png = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };
		String asset = "/api/layouts/" + id + "/assets/img-1";

		this.mvc.perform(put(asset).contentType(MediaType.IMAGE_PNG).content(png)).andExpect(status().isCreated());
		this.mvc.perform(put(asset).contentType(MediaType.IMAGE_PNG).content(png)).andExpect(status().isOk());
		this.mvc.perform(put(asset).contentType(MediaType.IMAGE_PNG).content(new byte[] { 9 }))
			.andExpect(status().isConflict());
		this.mvc.perform(put("/api/layouts/" + id + "/assets/img-2").contentType("image/svg+xml").content("<svg/>"))
			.andExpect(status().isBadRequest());

		MvcResult result = this.mvc.perform(get(asset))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
			.andReturn();
		assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(png);
	}

	@Test
	void answersNotFoundForUnknownLayoutsAndVersions() throws Exception {
		this.mvc.perform(get("/api/layouts/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
		String id = create(TestDocuments.stand()).get("id").asString();
		this.mvc.perform(get("/api/layouts/" + id + "/versions/1")).andExpect(status().isNotFound());
	}

	private ObjectNode create(ObjectNode document) throws Exception {
		MvcResult result = this.mvc
			.perform(post("/api/layouts").contentType(MediaType.APPLICATION_JSON).content(document.toString()))
			.andExpect(status().isCreated())
			.andReturn();
		return (ObjectNode) TestDocuments.JSON.readTree(result.getResponse().getContentAsString());
	}

}
