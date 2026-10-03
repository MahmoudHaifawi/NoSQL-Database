package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.service.ReadService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;

/**
 * Web UI for reading documents: list every document in a schema, or fetch one by id.
 */
@Controller
@AllArgsConstructor
@RequestMapping("/read")
public class ReadController {

    private final ReadService readService;

    @GetMapping
    public String page(HttpServletRequest request) {
        request.getSession();
        return "read";
    }

    @PostMapping("/all")
    public String readAll(@RequestParam String database,
                          @RequestParam String schema,
                          HttpServletRequest request,
                          Model model) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        try {
            var docs = readService.readAll(database, schema, token, nodeURL);
            model.addAttribute("docs", docs);
            model.addAttribute("count", docs.size());
        } catch (Exception e) {
            model.addAttribute("readError", e.getMessage());
        }
        model.addAttribute("f_database", database);
        model.addAttribute("f_schema", schema);
        return "read";
    }

    @PostMapping("/one")
    public String readOne(@RequestParam String database,
                          @RequestParam String schema,
                          @RequestParam String id,
                          HttpServletRequest request,
                          Model model) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        try {
            model.addAttribute("single", readService.readById(database, schema, id, token, nodeURL));
            model.addAttribute("singleId", id);
        } catch (Exception e) {
            model.addAttribute("readError", "Could not read document " + id + ": " + e.getMessage());
        }
        model.addAttribute("f_database", database);
        model.addAttribute("f_schema", schema);
        model.addAttribute("f_id", id);
        return "read";
    }
}
