package ai.susi.server.api.cms;

import ai.susi.DAO;
import ai.susi.json.JsonObjectWithDefault;
import ai.susi.json.JsonTray;
import org.json.JSONArray;
import org.json.JSONObject;
import ai.susi.mind.SusiSkill;
import ai.susi.server.APIHandler;
import ai.susi.server.AbstractAPIHandler;
import ai.susi.server.Authentication;
import ai.susi.server.Authorization;
import ai.susi.server.ClientCredential;
import ai.susi.server.ClientIdentity;
import ai.susi.server.Query;
import ai.susi.server.ServiceResponse;
import ai.susi.server.UserRole;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;

/**
 * Created by saurabh on 7/6/17.
 * Modified by Chetan Kaushik on 4/8/17
 * This Service creates an skill as per given query.
 * The skill name given in the query should not exist in the SUSI intents Folder
 * Can be tested on :-
 * http://localhost:4000/cms/createSkill.txt?model=general&group=Knowledge&language=en&skill=whois&content=skillData
 */

@MultipartConfig(fileSizeThreshold=1024*1024*10, 	// 10 MB
        maxFileSize=1024*1024*50,      	// 50 MB
        maxRequestSize=1024*1024*100)   	// 100 MB
public class CreateSkillService extends AbstractAPIHandler implements APIHandler {

    private static final long serialVersionUID = 2461878194569824151L;

    @Override
    public UserRole getMinimalUserRole() {
        return UserRole.USER;
    }

    @Override
    public JSONObject getDefaultPermissions(UserRole baseUserRole) {
        return null;
    }

    @Override
    public String getAPIPath() {
        return "/cms/createSkill.json";
    }
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        resp.setHeader("Access-Control-Allow-Origin", "*"); // enable CORS
        String userEmail = null;
        JSONObject json = new JSONObject();
        Part imagePart = req.getPart("image");
        if (req.getParameter("access_token") != null) {
            if (imagePart == null) {
                json.put("accepted", false);
                json.put("message", "Image not given");
            } else {
                String userId = null;
                if (req.getParameter("access_token") != null) { // access tokens can be used by api calls, somehow the stateless equivalent of sessions for browsers
                    ClientCredential credential = new ClientCredential(ClientCredential.Type.access_token, req.getParameter("access_token"));
                    Authentication authentication = DAO.getAuthentication(credential);
                    // check if access_token is valid
                    if (authentication.getIdentity() != null) {
                        ClientIdentity identity = authentication.getIdentity();
                        userEmail = identity.getName();
                        userId = identity.getUuid();
                    }
                }
                if (userId != null) {
                // if client sends private=1 then it is a private skill
                File private_skill_dir = null;
                String privateSkill = req.getParameter("private");
                if(privateSkill != null){
                    private_skill_dir = new File(DAO.private_skill_watch_dir,userId);
                }
                InputStream imagePartContent = imagePart.getInputStream();

                String model_name = req.getParameter("model");
                if (model_name == null) {
                    model_name = "general";
                }
                File model = new File(DAO.model_watch_dir, model_name);
                if(privateSkill != null){
                    model = private_skill_dir;
                }
                String group_name = req.getParameter("group");
                if (group_name == null) {
                    group_name = "Knowledge";
                }
                File group = new File(model, group_name);
                String language_name = req.getParameter("language");
                if (language_name == null) {
                    language_name = "en";
                }
                File language = new File(group, language_name);
                String skill_name = req.getParameter("skill");
                File skill = SusiSkill.getSkillFileInLanguage(language, skill_name, false);

                String image_name = req.getParameter("image_name");



                String imagePath = language.getPath() + File.separator + "images";
                if (image_name == null) {
                    // Checking for
                    json.put("accepted", false);
                    json.put("message", "The Image name are not given or Image with same name is already present ");

                } else {
                    // Checking for file existence
                    json.put("accepted", false);
                    if (skill.exists()) {
                        json.put("message", "The '" + skill + "' already exists.");
                    } else {
                        // Reading Content for skill
                        String content = req.getParameter("content");
                        if (content == null) content = "";

                        // Reading content for image
                        Image image = ImageIO.read(imagePartContent);
                        BufferedImage bi = this.createResizedCopy(image, 512, 512, true);

                        // Checks if images directory exists or not. If not then create one
                        if (!Files.exists(Paths.get(imagePath))) new File(imagePath).mkdirs();
                        File p = new File(imagePath + File.separator + image_name);
                        if (p.exists()) p.delete();
                        ImageIO.write(bi, "jpg", new File(imagePath + File.separator + image_name));

                        // Writing Skills Data in File
                        try (FileWriter Skillfile = new FileWriter(skill)) {
                            Skillfile.write(content);
                            String path = null;
                            if(privateSkill != null){
                                path = skill.getPath().replace(private_skill_dir.toString(), "users");
                            }
                            else{
                                path = skill.getPath().replace(DAO.model_watch_dir.toString(), "models");
                            }

                        } catch (IOException e) {
                            e.printStackTrace();
                            json.put("message", "error: " + e.getMessage());
                        }

                        //Add to git
                        if(privateSkill != null){
                            this.storePrivateSkillBot(skill, userId, skill_name, group_name, language_name);
                            try (Git git = DAO.getPrivateGit()) {
                                git.add().addFilepattern(".").call();

                            // commit the changes
                                DAO.pushCommit(git, "Created " + skill_name, userEmail);
                                json.put("accepted", true);

                            } catch (IOException | GitAPIException e) {
                                e.printStackTrace();
                                json.put("message", "error: " + e.getMessage());

                            }
                        }
                        else{
                            try (Git git = DAO.getGit()) {
                                git.add().addFilepattern(".").call();

                            // commit the changes
                                DAO.pushCommit(git, "Created " + skill_name, userEmail);
                                json.put("accepted", true);

                            } catch (IOException | GitAPIException e) {
                                e.printStackTrace();
                                json.put("message", "error: " + e.getMessage());

                            }
                        }

                    }
                }
              }
              else {
                json.put("message","Access token not valid");
                json.put("accepted",false);
              }
            }
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(json.toString());
        }
        else{
            json.put("message","Access token are not given");
            json.put("accepted",false);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(json.toString());
        }
    }

    public static BufferedImage createResizedCopy(Image originalImage, int scaledWidth, int scaledHeight, boolean preserveAlpha) {
        int imageType = preserveAlpha ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage scaledBI = new BufferedImage(scaledWidth, scaledHeight, imageType);
        Graphics2D g = scaledBI.createGraphics();
        if (preserveAlpha) {
            g.setComposite(AlphaComposite.Src);
        }
        g.drawImage(originalImage, 0, 0, scaledWidth, scaledHeight, null);
        g.dispose();
        return scaledBI;
    }

    /**
     * Utility method to get file name from HTTP header content-disposition
     */
    private static String getFileName(Part part) {
        String contentDisp = part.getHeader("content-disposition");
        System.out.println("content-disposition header= "+contentDisp);
        String[] tokens = contentDisp.split(";");
        for (String token : tokens) {
            if (token.trim().startsWith("filename")) {
                return token.substring(token.indexOf("=") + 2, token.length()-1);
            }
        }
        return "";
    }

    /**
    * Helper method to store the private skill bot of the user in chatbot.json file
    */
    private static void storePrivateSkillBot(File skill, String userId, String skill_name, String group_name, String language_name) {
        JsonTray chatbot = DAO.chatbot;
        JSONObject userName = new JSONObject();
        JSONObject groupName = new JSONObject();
        JSONObject languageName = new JSONObject();
        JSONObject designObject = new JSONObject();
        JSONObject configObject = new JSONObject();
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        if (chatbot.has(userId)) {
            userName = chatbot.getJSONObject(userId);
            if (userName.has(group_name)) {
                groupName = userName.getJSONObject(group_name);
                if (groupName.has(language_name)) {
                    languageName = groupName.getJSONObject(language_name);
                }
            }
        }
        // read design and configuration settings
        try {
            BufferedReader br = new BufferedReader(new FileReader(skill));
            String line = "";
            readloop: while ((line = br.readLine()) != null) {
                String linebeforetrim = line;
                line = line.trim();

                if (line.startsWith("::")) {
                  int thenpos=-1;
                  if (line.startsWith("::bodyBackgroundImage") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          designObject.put("bodyBackgroundImage",value);
                  }
                  else if (line.startsWith("::bodyBackground") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          designObject.put("bodyBackground",value);
                  }
                  else if (line.startsWith("::userMessageBoxBackground") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          designObject.put("userMessageBoxBackground",value);
                  }
                  else if (line.startsWith("::userMessageTextColor") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          designObject.put("userMessageTextColor",value);
                  }
                  else if (line.startsWith("::botMessageBoxBackground") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          designObject.put("botMessageBoxBackground",value);
                  }
                  else if (line.startsWith("::botMessageTextColor") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          designObject.put("botMessageTextColor",value);
                  }
                  else if (line.startsWith("::botIconColor") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          designObject.put("botIconColor",value);
                  }
                  else if (line.startsWith("::botIconImage") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          designObject.put("botIconImage",value);
                  }
                  else if (line.startsWith("::sites_enabled") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          configObject.put("sites_enabled",value);
                  }
                  else if (line.startsWith("::sites_disabled") && (thenpos = line.indexOf(' ')) > 0) {
                      String value = line.substring(thenpos + 1).trim();
                      if(value.length() > 0)
                          configObject.put("sites_disabled",value);
                  }
                }
              }
          } catch (IOException e) {
            DAO.log(e.getMessage());
          }

        // save a new bot
        JSONObject botObject = new JSONObject();
        botObject.put("design",designObject);
        botObject.put("configure",configObject);
        botObject.put("timestamp", timestamp.toString());
        languageName.put(skill_name, botObject);
        groupName.put(language_name, languageName);
        userName.put(group_name, groupName);
        chatbot.put(userId, userName, true);
    }

    @Override
    public ServiceResponse serviceImpl(Query call, HttpServletResponse response, Authorization rights, final JsonObjectWithDefault permissions) {

        return new ServiceResponse("");
    }
}
