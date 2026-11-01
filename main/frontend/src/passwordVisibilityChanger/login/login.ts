import VisibilityDirector from "../utils/PasswordVisibilityChanger/director/VisibilityDirector";
import ShowVisibility from "../utils/PasswordVisibilityChanger/classes/ShowVisibility";
import HideVisibility from "../utils/PasswordVisibilityChanger/classes/HideVisibility";

const icon : HTMLImageElement = document.querySelector(".show_hide_passwrd")!;
const field : HTMLInputElement = document.querySelector("#password")!;

icon.addEventListener("click", (e : MouseEvent) => {
    e.preventDefault();

    const status : string | undefined = icon?.dataset.status;

    const director : VisibilityDirector = new VisibilityDirector();

    if (status == "hide") {
        director.changer = new ShowVisibility();
    } else if (status == "show") {
        director.changer = new HideVisibility();
    }

    director.change(icon, field);
});
