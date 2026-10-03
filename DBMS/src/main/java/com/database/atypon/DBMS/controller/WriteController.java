package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.model.Schema;
import com.database.atypon.DBMS.service.WriteService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;

@Controller
@AllArgsConstructor
public class WriteController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WriteController.class);

    private final WriteService writeService;

    @PostMapping("/createSchema")
    public String createSchema(Schema schema, HttpServletRequest request, RedirectAttributes redirect) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        try {
            String result = writeService.createSchema(schema, token, nodeURL);
            redirect.addFlashAttribute("schemaMessage", result);
        } catch (Exception e) {
            log.warn("Create schema '{}' failed: {}", schema.getSchemaName(), e.getMessage());
            redirect.addFlashAttribute("schemaError", e.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/createDocument")
    public String createDocument(@RequestParam String databaseName,
                                 @RequestParam String schemaName,
                                 @RequestParam String document,
                                 HttpServletRequest request,
                                 RedirectAttributes redirect) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        try {
            String result = writeService.createDocument(databaseName, schemaName, document, token, nodeURL);
            redirect.addFlashAttribute("documentMessage", result);
        } catch (Exception e) {
            log.warn("Create document in '{}.{}' failed: {}", databaseName, schemaName, e.getMessage());
            redirect.addFlashAttribute("documentError", e.getMessage());
        }
        return "redirect:/dashboard";
    }

}
