package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.service.IndexService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web UI for the B+-tree secondary index: create an index (admin) and run range / equality /
 * ORDER BY / paginated queries against it (user), with matching documents rendered inline.
 */
@Controller
@AllArgsConstructor
@RequestMapping("/index")
public class IndexController {

    private final IndexService indexService;

    @GetMapping
    public String page(HttpServletRequest request, Model model) {
        requireLogin(request);
        return "index";
    }

    @PostMapping("/create")
    public String createIndex(@RequestParam String database,
                              @RequestParam String schema,
                              @RequestParam String field,
                              HttpServletRequest request,
                              RedirectAttributes redirect) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        try {
            String message = indexService.createIndex(database, schema, field, token, nodeURL);
            redirect.addFlashAttribute("indexMessage", message);
        } catch (Exception e) {
            redirect.addFlashAttribute("indexError", e.getMessage());
        }
        return "redirect:/index";
    }

    @PostMapping("/query")
    public String query(@RequestParam String database,
                        @RequestParam String schema,
                        @RequestParam String field,
                        @RequestParam String op,
                        @RequestParam(required = false) String value,
                        @RequestParam(required = false) String high,
                        @RequestParam(required = false) String order,
                        @RequestParam(required = false) Integer limit,
                        @RequestParam(required = false) Integer offset,
                        HttpServletRequest request,
                        Model model) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");

        Map<String, Object> body = new HashMap<>();
        body.put("database", database);
        body.put("schema", schema);
        body.put("field", field);
        body.put("op", op);
        if (value != null && !value.isEmpty()) body.put("value", value);
        if (high != null && !high.isEmpty()) body.put("high", high);
        if (order != null && !order.isEmpty()) body.put("order", order);
        if (limit != null) body.put("limit", limit);
        if (offset != null) body.put("offset", offset);

        try {
            LinkedHashMap<Integer, String> results = indexService.query(body, token, nodeURL);
            model.addAttribute("results", results);
            model.addAttribute("resultCount", results.size());
        } catch (Exception e) {
            model.addAttribute("queryError", e.getMessage());
        }

        // Echo the submitted values so the form stays populated after a query.
        model.addAttribute("f_database", database);
        model.addAttribute("f_schema", schema);
        model.addAttribute("f_field", field);
        model.addAttribute("f_op", op);
        model.addAttribute("f_value", value);
        model.addAttribute("f_high", high);
        model.addAttribute("f_order", order);
        model.addAttribute("f_limit", limit);
        model.addAttribute("f_offset", offset);
        return "index";
    }

    private void requireLogin(HttpServletRequest request) {
        // Session is established at /login; the data-node URL + token live there. No extra state here.
        request.getSession();
    }
}
