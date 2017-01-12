/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.ui.ws;

import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.config.MapSettings;
import org.sonar.api.config.Settings;
import org.sonar.api.platform.Server;
import org.sonar.api.resources.ResourceType;
import org.sonar.api.resources.ResourceTypeTree;
import org.sonar.api.resources.ResourceTypes;
import org.sonar.api.web.page.Page;
import org.sonar.api.web.page.PageDefinition;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.platform.PluginRepository;
import org.sonar.db.Database;
import org.sonar.db.dialect.H2;
import org.sonar.db.dialect.MySql;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ui.PageRepository;
import org.sonar.server.ws.WsActionTester;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonar.test.JsonAssert.assertJson;

public class GlobalActionTest {

  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();

  private Settings settings = new MapSettings();

  private Server server = mock(Server.class);
  private Database database = mock(Database.class);

  private WsActionTester ws;

  @Test
  public void empty_call() throws Exception {
    init();

    executeAndVerify("empty.json");
  }

  @Test
  public void return_qualifiers() throws Exception {
    init(new Page[] {}, new ResourceTypeTree[] {
      ResourceTypeTree.builder()
        .addType(ResourceType.builder("POL").build())
        .addType(ResourceType.builder("LOP").build())
        .addRelations("POL", "LOP")
        .build(),
      ResourceTypeTree.builder()
        .addType(ResourceType.builder("PAL").build())
        .addType(ResourceType.builder("LAP").build())
        .addRelations("PAL", "LAP")
        .build()
    });

    executeAndVerify("qualifiers.json");
  }

  @Test
  public void return_settings() throws Exception {
    init();
    settings.setProperty("sonar.lf.logoUrl", "http://example.com/my-custom-logo.png");
    settings.setProperty("sonar.lf.logoWidthPx", 135);
    settings.setProperty("sonar.lf.gravatarServerUrl", "https://secure.gravatar.com/avatar/{EMAIL_MD5}.jpg?s={SIZE}&d=identicon");
    settings.setProperty("sonar.lf.enableGravatar", true);
    settings.setProperty("sonar.updatecenter.activate", false);
    settings.setProperty("sonar.technicalDebt.hoursInDay", "10");
    settings.setProperty("sonar.technicalDebt.ratingGrid", "0.05,0.1,0.2,0.5");
    settings.setProperty("sonar.allowUsersToSignUp", true);
    // This setting should be ignored as it's not needed
    settings.setProperty("sonar.defaultGroup", "sonar-users");

    executeAndVerify("settings.json");
  }

  @Test
  public void return_deprecated_logo_settings() throws Exception {
    init();
    settings.setProperty("sonar.lf.logoUrl", "http://example.com/my-custom-logo.png");
    settings.setProperty("sonar.lf.logoWidthPx", 135);

    executeAndVerify("deprecated_logo_settings.json");
  }

  @Test
  public void return_global_pages_for_anonymous() throws Exception {
    init(createPages(), new ResourceTypeTree[] {});

    executeAndVerify("global_pages_for_anonymous.json");
  }

  @Test
  public void return_global_pages_for_user() throws Exception {
    init(createPages(), new ResourceTypeTree[] {});
    userSessionRule.login("obiwan");

    executeAndVerify("global_pages_for_user.json");
  }

  @Test
  public void return_global_pages_for_admin_user() throws Exception {
    init(createPages(), new ResourceTypeTree[] {});
    userSessionRule.login("obiwan").setGlobalPermissions(GlobalPermissions.SYSTEM_ADMIN);

    executeAndVerify("global_pages_for_admin.json");
  }

  @Test
  public void return_sonarqube_version() throws Exception {
    init();
    when(server.getVersion()).thenReturn("6.2");

    executeAndVerify("version.json");
  }

  @Test
  public void return_if_production_database_or_not() throws Exception {
    init();
    when(database.getDialect()).thenReturn(new MySql());

    executeAndVerify("production_database.json");
  }

  @Test
  public void test_example_response() throws Exception {
    init(createPages(), new ResourceTypeTree[] {
      ResourceTypeTree.builder()
        .addType(ResourceType.builder("POL").build())
        .addType(ResourceType.builder("LOP").build())
        .addRelations("POL", "LOP")
        .build(),
      ResourceTypeTree.builder()
        .addType(ResourceType.builder("PAL").build())
        .addType(ResourceType.builder("LAP").build())
        .addRelations("PAL", "LAP")
        .build()
    });
    settings.setProperty("sonar.lf.logoUrl", "http://example.com/my-custom-logo.png");
    settings.setProperty("sonar.lf.logoWidthPx", 135);
    settings.setProperty("sonar.lf.gravatarServerUrl", "http://some-server.tld/logo.png");
    settings.setProperty("sonar.lf.enableGravatar", true);
    settings.setProperty("sonar.updatecenter.activate", false);
    settings.setProperty("sonar.technicalDebt.hoursInDay", "10");
    settings.setProperty("sonar.technicalDebt.ratingGrid", "0.05,0.1,0.2,0.5");
    settings.setProperty("sonar.allowUsersToSignUp", true);
    when(server.getVersion()).thenReturn("6.2");
    when(database.getDialect()).thenReturn(new MySql());

    String result = ws.newRequest().execute().getInput();
    assertJson(ws.getDef().responseExampleAsString()).isSimilarTo(result);
  }

  private void init() {
    init(new org.sonar.api.web.page.Page[] {}, new ResourceTypeTree[] {});
  }

  private void init(org.sonar.api.web.page.Page[] pages, ResourceTypeTree[] resourceTypeTrees) {
    when(database.getDialect()).thenReturn(new H2());
    PluginRepository pluginRepository = mock(PluginRepository.class);
    when(pluginRepository.hasPlugin(anyString())).thenReturn(true);
    PageRepository pageRepository = new PageRepository(pluginRepository, new PageDefinition[] {context -> {
      for (Page page : pages) {
        context.addPage(page);
      }
    }});
    pageRepository.start();
    ws = new WsActionTester(new GlobalAction(pageRepository, settings, new ResourceTypes(resourceTypeTrees), server, database));
  }

  private void executeAndVerify(String json) {
    assertJson(ws.newRequest().execute().getInput()).isSimilarTo(getClass().getResource(GlobalActionTest.class.getSimpleName() + "/" + json));
  }

  private Page[] createPages() {
    Page page = Page.builder("my_plugin/page").setName("My Plugin Page").build();
    Page anotherPage = Page.builder("another_plugin/page").setName("My Another Page").build();
    Page adminPage = Page.builder("my_plugin/admin_page").setName("Admin Page").setAdmin(true).build();

    return new Page[] {page, anotherPage, adminPage};
  }
}