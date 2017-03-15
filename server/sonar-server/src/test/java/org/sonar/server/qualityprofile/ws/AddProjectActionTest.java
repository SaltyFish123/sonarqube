/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
package org.sonar.server.qualityprofile.ws;

import java.net.HttpURLConnection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.resources.Languages;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.qualityprofile.QualityProfileDto;
import org.sonar.server.component.ComponentFinder;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.language.LanguageTesting;
import org.sonar.server.qualityprofile.QProfileLookup;
import org.sonar.server.qualityprofile.QProfileProjectOperations;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.TestResponse;
import org.sonar.server.ws.WsActionTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.db.permission.OrganizationPermission.ADMINISTER_QUALITY_PROFILES;

public class AddProjectActionTest {

  private static final String LANGUAGE_1 = "xoo";
  private static final String LANGUAGE_2 = "foo";

  @Rule
  public DbTester db = DbTester.create();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();

  private DbClient dbClient = db.getDbClient();
  private QProfileProjectOperations qProfileProjectOperations = new QProfileProjectOperations(dbClient, userSession);
  private Languages languages = LanguageTesting.newLanguages(LANGUAGE_1, LANGUAGE_2);
  private ProjectAssociationParameters projectAssociationParameters = new ProjectAssociationParameters(languages);
  private AddProjectAction underTest = new AddProjectAction(projectAssociationParameters, qProfileProjectOperations,
    new ProjectAssociationFinder(new QProfileLookup(dbClient),
      new ComponentFinder(dbClient)),
    dbClient);
  private WsActionTester tester = new WsActionTester(underTest);

  @Test
  public void test_definition() {
    WebService.Action definition = tester.getDef();
    assertThat(definition.since()).isEqualTo("5.2");
    assertThat(definition.isPost()).isTrue();
    assertThat(definition.params()).extracting(WebService.Param::key).containsOnly("profileKey", "profileName", "projectKey", "language", "projectUuid");
  }

  @Test
  public void add_project_on_profile_of_default_organization() {
    logInAsProfileAdmin();
    ComponentDto project = db.components().insertProject(db.getDefaultOrganization());
    QualityProfileDto profile = db.qualityProfiles().insert(db.getDefaultOrganization());

    TestResponse response = call(project, profile);
    assertThat(response.getStatus()).isEqualTo(HttpURLConnection.HTTP_NO_CONTENT);

    assertProjectIsAssociatedToProfile(project, profile);
  }

  @Test
  public void change_association_in_default_organization() throws Exception {
    logInAsProfileAdmin();

    ComponentDto project = db.components().insertProject(db.getDefaultOrganization());
    // two profiles on same language
    QualityProfileDto profile1 = db.qualityProfiles().insert(db.getDefaultOrganization(), p -> p.setLanguage(LANGUAGE_1));
    QualityProfileDto profile2 = db.qualityProfiles().insert(db.getDefaultOrganization(), p -> p.setLanguage(LANGUAGE_1));
    db.qualityProfiles().associateProjectWithQualityProfile(project, profile1);

    call(project, profile2);

    assertProjectIsNotAssociatedToProfile(project, profile1);
    assertProjectIsAssociatedToProfile(project, profile2);
  }

  @Test
  public void changing_association_does_not_change_other_language_associations() throws Exception {
    logInAsProfileAdmin();
    ComponentDto project = db.components().insertProject(db.getDefaultOrganization());
    QualityProfileDto profile1Language1 = db.qualityProfiles().insert(db.getDefaultOrganization(), p -> p.setLanguage(LANGUAGE_1));
    QualityProfileDto profile2Language2 = db.qualityProfiles().insert(db.getDefaultOrganization(), p -> p.setLanguage(LANGUAGE_2));
    QualityProfileDto profile3Language1 = db.qualityProfiles().insert(db.getDefaultOrganization(), p -> p.setLanguage(LANGUAGE_1));
    db.qualityProfiles().associateProjectWithQualityProfile(project, profile1Language1, profile2Language2);

    call(project, profile3Language1);

    assertProjectIsAssociatedToProfile(project, profile3Language1);
    assertProjectIsAssociatedToProfile(project, profile2Language2);
  }

  @Test
  public void project_administrator_can_change_profile() throws Exception {
    ComponentDto project = db.components().insertProject(db.getDefaultOrganization());
    QualityProfileDto profile = db.qualityProfiles().insert(db.getDefaultOrganization());
    userSession.logIn().addProjectUuidPermissions(UserRole.ADMIN, project.uuid());

    call(project, profile);

    assertProjectIsAssociatedToProfile(project, profile);
  }

  @Test
  public void throw_ForbiddenException_if_not_project_nor_organization_administrator() {
    userSession.logIn();
    ComponentDto project = db.components().insertProject(db.getDefaultOrganization());
    QualityProfileDto profile = db.qualityProfiles().insert(db.getDefaultOrganization());

    expectedException.expect(ForbiddenException.class);
    expectedException.expectMessage("Insufficient privileges");

    call(project, profile);
  }

  @Test
  public void throw_ForbiddenException_if_not_logged_in() {
    userSession.anonymous();
    ComponentDto project = db.components().insertProject(db.getDefaultOrganization());
    QualityProfileDto profile = db.qualityProfiles().insert(db.getDefaultOrganization());

    expectedException.expect(ForbiddenException.class);
    expectedException.expectMessage("Insufficient privileges");

    call(project, profile);
  }

  @Test
  public void throw_NotFoundException_if_project_does_not_exist() {
    logInAsProfileAdmin();
    QualityProfileDto profile = db.qualityProfiles().insert(db.getDefaultOrganization());

    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("Component id 'unknown' not found");

    tester.newRequest()
      .setParam("projectUuid", "unknown")
      .setParam("profileKey", profile.getKey())
      .execute();
  }

  @Test
  public void throw_NotFoundException_if_profile_does_not_exist() {
    logInAsProfileAdmin();
    ComponentDto project = db.components().insertProject(db.getDefaultOrganization());

    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("Quality profile does not exist");

    tester.newRequest()
      .setParam("projectUuid", project.uuid())
      .setParam("profileKey", "unknown")
      .execute();
  }

  private void assertProjectIsAssociatedToProfile(ComponentDto project, QualityProfileDto profile) {
    QualityProfileDto loaded = dbClient.qualityProfileDao().selectByProjectAndLanguage(db.getSession(), project.getKey(), profile.getLanguage());
    assertThat(loaded.getKey()).isEqualTo(profile.getKey());
  }

  private void assertProjectIsNotAssociatedToProfile(ComponentDto project, QualityProfileDto profile) {
    QualityProfileDto loaded = dbClient.qualityProfileDao().selectByProjectAndLanguage(db.getSession(), project.getKey(), profile.getLanguage());
    assertThat(loaded == null || !loaded.getKey().equals(profile.getKey())).isTrue();
  }

  private void logInAsProfileAdmin() {
    userSession.logIn().addPermission(ADMINISTER_QUALITY_PROFILES, db.getDefaultOrganization());
  }

  private TestResponse call(ComponentDto project, QualityProfileDto qualityProfile) {
    TestRequest request = tester.newRequest()
      .setParam("projectUuid", project.uuid())
      .setParam("profileKey", qualityProfile.getKey());
    return request.execute();
  }

}
