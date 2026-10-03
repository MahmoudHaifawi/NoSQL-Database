package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.service.ReadService;
import com.database.atypon.DBMS.service.WriteService;
import lombok.AllArgsConstructor;
import org.json.JSONObject;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;

/**
 * Fetch-then-edit UI for document update with optimistic locking. Fetch loads the current
 * document and splits off its {@code _version} into a hidden field; Save re-attaches it as the
 * expected version. On a version conflict the page re-fetches so the user can re-apply their
 * change against the latest version.
 */
@Controller
@AllArgsConstructor
@RequestMapping("/update")
public class UpdateController {

    private final WriteService writeService;
    private final ReadService readService;

    @GetMapping
    public String page(HttpServletRequest request) {
        request.getSession();
        return "update";
    }

    @PostMapping("/fetch")
    public String fetch(@RequestParam String database, @RequestParam String schema,
                        @RequestParam String id, HttpServletRequest request, Model model) {
        loadForEdit(database, schema, id, request, model);
        return "update";
    }

    @PostMapping("/save")
    public String save(@RequestParam String database, @RequestParam String schema,
                       @RequestParam String id, @RequestParam String document,
                       @RequestParam int version, HttpServletRequest request, Model model) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        model.addAttribute("f_database", database);
        model.addAttribute("f_schema", schema);
        model.addAttribute("f_id", id);
        try {
            int newVersion = writeService.updateDocument(database, schema, id, document, version, token, nodeURL);
            model.addAttribute("updateMessage", "Updated to version " + newVersion);
            loadForEdit(database, schema, id, request, model); // reload with the new version
        } catch (Exception e) {
            model.addAttribute("updateError", e.getMessage());
            loadForEdit(database, schema, id, request, model); // reload current (resolves a conflict)
        }
        return "update";
    }

    private void loadForEdit(String database, String schema, String id, HttpServletRequest request, Model model) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        model.addAttribute("f_database", database);
        model.addAttribute("f_schema", schema);
        model.addAttribute("f_id", id);
        try {
            JSONObject doc = new JSONObject(readService.readById(database, schema, id, token, nodeURL));
            int version = doc.optInt("_version", 1);
            doc.remove("_version");
            model.addAttribute("docJson", doc.toString(2));
            model.addAttribute("version", version);
            model.addAttribute("loaded", true);
        } catch (Exception e) {
            model.addAttribute("updateError", "Could not read document " + id + ": " + e.getMessage());
        }
    }
}
